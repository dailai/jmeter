/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.jmeter.save;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.jmeter.reporters.ResultCollectorHelper;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.NameUpdater;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.util.JMeterError;
import org.apache.jorphan.util.JOrphanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.DataHolder;
import com.thoughtworks.xstream.converters.reflection.PureJavaReflectionProvider;
import com.thoughtworks.xstream.converters.reflection.ReflectionProvider;
import com.thoughtworks.xstream.io.xml.XppDriver;
import com.thoughtworks.xstream.mapper.CannotResolveClassException;
import com.thoughtworks.xstream.mapper.Mapper;
import com.thoughtworks.xstream.mapper.MapperWrapper;

/**
 * Handles setting up XStream serialisation.
 * The class reads alias definitions from saveservice.properties.
 *
 */
public class SaveService {

    private static final Logger log = LoggerFactory.getLogger(SaveService.class);

    // Names of DataHolder entries for JTL processing
    public static final String SAMPLE_EVENT_OBJECT = "SampleEvent"; // $NON-NLS-1$
    public static final String RESULTCOLLECTOR_HELPER_OBJECT = "ResultCollectorHelper"; // $NON-NLS-1$

    // Names of DataHolder entries for JMX processing
    public static final String TEST_CLASS_NAME = "TestClassName"; // $NON-NLS-1$

    private static final class XStreamWrapper extends XStream {
        private XStreamWrapper(ReflectionProvider reflectionProvider) {
            super(reflectionProvider);
        }

        // Override wrapMapper in order to insert the Wrapper in the chain
        @Override
        protected MapperWrapper wrapMapper(MapperWrapper next) {
            // Provide our own aliasing using strings rather than classes
            return new MapperWrapper(next){
            // Translate alias to classname and then delegate to wrapped class
            @Override
            public Class<?> realClass(String alias) {
                String fullName = aliasToClass(alias);
                if (fullName != null) {
                    fullName = NameUpdater.getCurrentName(fullName);
                }
                return super.realClass(fullName == null ? alias : fullName);
            }
            // Translate to alias and then delegate to wrapped class
            @Override
            public String serializedClass(@SuppressWarnings("rawtypes") // superclass does not use types 
                    Class type) {
                if (type == null) {
                    return super.serializedClass(null); // was type, but that caused FindBugs warning
                }
                String alias = classToAlias(type.getName());
                return alias == null ? super.serializedClass(type) : alias ;
                }
            };
        }
    }

    private static final XStream JMXSAVER = new XStreamWrapper(new PureJavaReflectionProvider());
    private static final XStream JTLSAVER = new XStreamWrapper(new PureJavaReflectionProvider());
    static {
        JTLSAVER.setMode(XStream.NO_REFERENCES); // This is needed to stop XStream keeping copies of each class
        JMeterUtils.setupXStreamSecurityPolicy(JMXSAVER);
        JMeterUtils.setupXStreamSecurityPolicy(JTLSAVER);
    }

    // The XML header, with placeholder for encoding, since that is controlled by property
    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"<ph>\"?>"; // $NON-NLS-1$

    // Default file name
    private static final String SAVESERVICE_PROPERTIES_FILE = "/bin/saveservice.properties"; // $NON-NLS-1$

    // Property name used to define file name
    private static final String SAVESERVICE_PROPERTIES = "saveservice_properties"; // $NON-NLS-1$

    // Define file format versions
    private static final String VERSION_2_2 = "2.2";  // $NON-NLS-1$

    // Holds the mappings from the saveservice properties file
    // Key: alias Entry: full class name
    // There may be multiple aliases which map to the same class
    private static final Properties aliasToClass = new Properties();

    // Holds the reverse mappings
    // Key: full class name Entry: primary alias
    private static final Properties classToAlias = new Properties();

    // Version information for test plan header
    // This is written to JMX files by ScriptWrapperConverter
    // Also to JTL files by ResultCollector
    private static final String VERSION = "1.2"; // $NON-NLS-1$

    // This is written to JMX files by ScriptWrapperConverter
    private static String propertiesVersion = "";// read from properties file; written to JMX files
    
    // Must match _version property value in saveservice.properties
    // used to ensure saveservice.properties and SaveService are updated simultaneously
    static final String PROPVERSION = "4.0";// Expected version $NON-NLS-1$

    // Internal information only
    private static String fileVersion = ""; // computed from saveservice.properties file// $NON-NLS-1$
    // Must match the sha1 checksum of the file saveservice.properties (without newline character),
    // used to ensure saveservice.properties and SaveService are updated simultaneously
    static final String FILEVERSION = "83cad39b5062586b5bf2e704b2460af2e108c2aa"; // Expected value $NON-NLS-1$

    private static String fileEncoding = ""; // read from properties file// $NON-NLS-1$

    static {
        log.info("Testplan (JMX) version: {}. Testlog (JTL) version: {}", VERSION_2_2, VERSION_2_2);
        initProps();
        checkVersions();
    }

    // Helper method to simplify alias creation from properties
    private static void makeAlias(String aliasList, String clazz) {
        String[] aliases = aliasList.split(","); // Can have multiple aliases for same target classname
        String alias = aliases[0];
        for (String a : aliases){
            Object old = aliasToClass.setProperty(a,clazz);
            if (old != null){
                log.error("Duplicate class detected for {}: {} & {}", alias, clazz, old);
            }
        }
        Object oldval=classToAlias.setProperty(clazz,alias);
        if (oldval != null) {
            log.error("Duplicate alias detected for {}: {} & {}", clazz, alias, oldval);
        }
    }

    public static Properties loadProperties() throws IOException{
        Properties nameMap = new Properties();
        try (FileInputStream fis = new FileInputStream(JMeterUtils.getJMeterHome()
                + JMeterUtils.getPropDefault(SAVESERVICE_PROPERTIES, SAVESERVICE_PROPERTIES_FILE))){
            nameMap.load(fis);
        }
        return nameMap;
    }

    private static String getChecksumForPropertiesFile()
            throws NoSuchAlgorithmException, IOException {
        MessageDigest md = MessageDigest.getInstance("SHA1");
        try (BufferedReader reader = 
                Files.newBufferedReader(new File(JMeterUtils.getJMeterHome()
                    + JMeterUtils.getPropDefault(SAVESERVICE_PROPERTIES,
                    SAVESERVICE_PROPERTIES_FILE)).toPath(), Charset.defaultCharset())) {
            String line = null;
            while ((line = reader.readLine()) != null) {
                md.update(line.getBytes());
            }
        }
        return JOrphanUtils.baToHexString(md.digest());
    }
    private static void initProps() {
        // Load the alias properties
        try {
            fileVersion = getChecksumForPropertiesFile();
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("Can't compute checksum for saveservice properties file", e);
            throw new JMeterError("JMeter requires the checksum of saveservice properties file to continue", e);
        }
        try {
            Properties nameMap = loadProperties();
            // now create the aliases
            for (Map.Entry<Object, Object> me : nameMap.entrySet()) {
                String key = (String) me.getKey();
                String val = (String) me.getValue();
                if (!key.startsWith("_")) { // $NON-NLS-1$
                    makeAlias(key, val);
                } else {
                    // process special keys
                    if (key.equalsIgnoreCase("_version")) { // $NON-NLS-1$
                        propertiesVersion = val;
                        log.info("Using SaveService properties version {}", propertiesVersion);
                    } else if (key.equalsIgnoreCase("_file_version")) { // $NON-NLS-1$
                        log.info("SaveService properties file version is now computed by a checksum,"
                                + "the property _file_version is not used anymore and can be removed.");
                    } else if (key.equalsIgnoreCase("_file_encoding")) { // $NON-NLS-1$
                        fileEncoding = val;
                        log.info("Using SaveService properties file encoding {}", fileEncoding);
                    } else {
                        key = key.substring(1);// Remove the leading "_"
                        try {
                            final String trimmedValue = val.trim();
                            if (trimmedValue.equals("collection") // $NON-NLS-1$
                             || trimmedValue.equals("mapping")) { // $NON-NLS-1$
                                registerConverter(key, JMXSAVER, true);
                                registerConverter(key, JTLSAVER, true);
                            } else {
                                registerConverter(key, JMXSAVER, false);
                                registerConverter(key, JTLSAVER, false);
                            }
                        } catch (IllegalAccessException | InstantiationException | ClassNotFoundException | IllegalArgumentException|
                                SecurityException | InvocationTargetException | NoSuchMethodException e1) {
                            log.warn("Can't register a converter: {}", key, e1);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("Bad saveservice properties file", e);
            throw new JMeterError("JMeter requires the saveservice properties file to continue");
        }
    }

    /**
     * Register converter.
     * @param key
     * @param jmxsaver
     * @param useMapper
     *
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws NoSuchMethodException
     * @throws ClassNotFoundException
     */
    private static void registerConverter(String key, XStream jmxsaver, boolean useMapper)
            throws InstantiationException, IllegalAccessException,
            InvocationTargetException, NoSuchMethodException,
            ClassNotFoundException {
        if (useMapper){
            jmxsaver.registerConverter((Converter) Class.forName(key).getConstructor(
                    new Class[] { Mapper.class }).newInstance(
                            new Object[] { jmxsaver.getMapper() }));
        } else {
            jmxsaver.registerConverter((Converter) Class.forName(key).newInstance());
        }
    }

    // For converters to use
    public static String aliasToClass(String s){
        String r = aliasToClass.getProperty(s);
        return r == null ? s : r;
    }

    // For converters to use
    public static String classToAlias(String s){
        String r = classToAlias.getProperty(s);
        return r == null ? s : r;
    }

    // Called by Save function
    public static void saveTree(HashTree tree, OutputStream out) throws IOException {
        // Get the OutputWriter to use
        OutputStreamWriter outputStreamWriter = getOutputStreamWriter(out);
        writeXmlHeader(outputStreamWriter);
        // Use deprecated method, to avoid duplicating code
        ScriptWrapper wrapper = new ScriptWrapper();
        wrapper.testPlan = tree;
        JMXSAVER.toXML(wrapper, outputStreamWriter);
        outputStreamWriter.write('\n');// Ensure terminated properly
        outputStreamWriter.close();
    }

    // Used by Test code
    public static void saveElement(Object el, OutputStream out) throws IOException {
        // Get the OutputWriter to use
        OutputStreamWriter outputStreamWriter = getOutputStreamWriter(out);
        writeXmlHeader(outputStreamWriter);
        // Use deprecated method, to avoid duplicating code
        JMXSAVER.toXML(el, outputStreamWriter);
        outputStreamWriter.close();
    }

    // Used by Test code
    public static Object loadElement(InputStream in) throws IOException {
        // Get the InputReader to use
        InputStreamReader inputStreamReader = getInputStreamReader(in);
        // Use deprecated method, to avoid duplicating code
        Object element = JMXSAVER.fromXML(inputStreamReader);
        inputStreamReader.close();
        return element;
    }

    /**
     * Save a sampleResult to an XML output file using XStream.
     *
     * @param evt sampleResult wrapped in a sampleEvent
     * @param writer output stream which must be created using {@link #getFileEncoding(String)}
     * @throws IOException when writing data to output fails
     */
    // Used by ResultCollector.sampleOccurred(SampleEvent event)
    public synchronized static void saveSampleResult(SampleEvent evt, Writer writer) throws IOException {
        DataHolder dh = JTLSAVER.newDataHolder();
        dh.put(SAMPLE_EVENT_OBJECT, evt);
        // This is effectively the same as saver.toXML(Object, Writer) except we get to provide the DataHolder
        // Don't know why there is no method for this in the XStream class
        try {
            JTLSAVER.marshal(evt.getResult(), new XppDriver().createWriter(writer), dh);
        } catch(RuntimeException e) {
            throw new IllegalArgumentException("Failed marshalling:"+(evt.getResult() != null ? showDebuggingInfo(evt.getResult()) : "null"), e);
        }
        writer.write('\n');
    }

    /**
     * 
     * @param result SampleResult
     * @return String debugging information
     */
    private static String showDebuggingInfo(SampleResult result) {
        try {
            return "class:"+result.getClass()+",content:"+ToStringBuilder.reflectionToString(result);
        } catch(Exception e) {
            return "Exception occurred creating debug from event, message:"+e.getMessage();
        }
    }

    // Routines for TestSaveService
    static String getPropertyVersion(){
        return SaveService.propertiesVersion;
    }

    static String getFileVersion(){
        return SaveService.fileVersion;
    }

    // Allow test code to check for spurious class references
    static List<String> checkClasses(){
        final ClassLoader classLoader = SaveService.class.getClassLoader();
        List<String> missingClasses = new ArrayList<>();
        for (Object clazz : classToAlias.keySet()) {
            String name = (String) clazz;
            if (!NameUpdater.isMapped(name)) {// don't bother checking class is present if it is to be updated
                try {
                    Class.forName(name, false, classLoader);
                } catch (ClassNotFoundException e) {
                        log.error("Unexpected entry in saveservice.properties; class does not exist and is not upgraded: {}", name);
                        missingClasses.add(name);
                }
            }
        }
        return missingClasses;
    }

    private static void checkVersions() {
        if (!PROPVERSION.equalsIgnoreCase(propertiesVersion)) {
            log.warn("Bad _version - expected {}, found {}.", PROPVERSION, propertiesVersion);
        }
    }

    /**
     * Read results from JTL file.
     *
     * @param reader of the file
     * @param resultCollectorHelper helper class to enable TestResultWrapperConverter to deliver the samples
     * @throws IOException if an I/O error occurs
     */
    public static void loadTestResults(InputStream reader, ResultCollectorHelper resultCollectorHelper) throws IOException {
        // Get the InputReader to use
        InputStreamReader inputStreamReader = getInputStreamReader(reader);
        DataHolder dh = JTLSAVER.newDataHolder();
        dh.put(RESULTCOLLECTOR_HELPER_OBJECT, resultCollectorHelper); // Allow TestResultWrapper to feed back the samples
        // This is effectively the same as saver.fromXML(InputStream) except we get to provide the DataHolder
        // Don't know why there is no method for this in the XStream class
        JTLSAVER.unmarshal(new XppDriver().createReader(reader), null, dh);
        inputStreamReader.close();
    }
    
    /**
     * Load a Test tree (JMX file)
     * @param file the JMX file
     * @return the loaded tree
     * @throws IOException if there is a problem reading the file or processing it
     */
    public static HashTree loadTree(File file) throws IOException {
        log.info("Loading file: {}", file);
        try (InputStream inputStream = new FileInputStream(file);
                BufferedInputStream bufferedInputStream = 
                    new BufferedInputStream(inputStream)){
            return readTree(bufferedInputStream, file);
        }
    }

    /**
     * 
     * @param inputStream {@link InputStream} 
     * @param file the JMX file used only for debug, can be null
     * @return the loaded tree
     * @throws IOException if there is a problem reading the file or processing it
     */
    private static HashTree readTree(InputStream inputStream, File file)
            throws IOException {
        ScriptWrapper wrapper = null;
        try {
            // Get the InputReader to use
            InputStreamReader inputStreamReader = getInputStreamReader(inputStream);
            wrapper = (ScriptWrapper) JMXSAVER.fromXML(inputStreamReader);
            inputStreamReader.close();
            if (wrapper == null){
                log.error("Problem loading XML: see above.");
                return null;
            }
            return wrapper.testPlan;
        } catch (CannotResolveClassException e) {
            if(file != null) {
                throw new IllegalArgumentException("Problem loading XML from:'"+file.getAbsolutePath()+"', cannot determine class for element: " + e, e);
            } else {
                throw new IllegalArgumentException("Problem loading XML, cannot determine class for element: " + e, e);
            }
        } catch (ConversionException | NoClassDefFoundError e) {
            if(file != null) {
                throw new IllegalArgumentException("Problem loading XML from:'"+file.getAbsolutePath()+"', missing class "+e , e);
            } else {
                throw new IllegalArgumentException("Problem loading XML, missing class "+e , e);
            }
        }

    }
    private static InputStreamReader getInputStreamReader(InputStream inStream) {
        // Check if we have a encoding to use from properties
        Charset charset = getFileEncodingCharset();
        return new InputStreamReader(inStream, charset);
    }

    private static OutputStreamWriter getOutputStreamWriter(OutputStream outStream) {
        // Check if we have a encoding to use from properties
        Charset charset = getFileEncodingCharset();
        return new OutputStreamWriter(outStream, charset);
    }

    /**
     * Returns the file Encoding specified in saveservice.properties or the default
     * @param dflt value to return if file encoding was not provided
     *
     * @return file encoding or default
     */
    // Used by ResultCollector when creating output files
    public static String getFileEncoding(String dflt){
        if(fileEncoding != null && fileEncoding.length() > 0) {
            return fileEncoding;
        }
        else {
            return dflt;
        }
    }

    // @NotNull
    private static Charset getFileEncodingCharset() {
        // Check if we have a encoding to use from properties
        if(fileEncoding != null && fileEncoding.length() > 0) {
            return Charset.forName(fileEncoding);
        }
        else {
            
            // We use the default character set encoding of the JRE
            log.info("fileEncoding not defined - using JRE default");
            return Charset.defaultCharset();
        }
    }

    private static void writeXmlHeader(OutputStreamWriter writer) throws IOException {
        // Write XML header if we have the charset to use for encoding
        Charset charset = getFileEncodingCharset();
        // We do not use getEncoding method of Writer, since that returns
        // the historical name
        String header = XML_HEADER.replaceAll("<ph>", charset.name());
        writer.write(header);
        writer.write('\n');
    }

//  Normal output
//  ---- Debugging information ----
//  required-type       : org.apache.jorphan.collections.ListedHashTree
//  cause-message       : WebServiceSampler : WebServiceSampler
//  class               : org.apache.jmeter.save.ScriptWrapper
//  message             : WebServiceSampler : WebServiceSampler
//  line number         : 929
//  path                : /jmeterTestPlan/hashTree/hashTree/hashTree[4]/hashTree[5]/WebServiceSampler
//  cause-exception     : com.thoughtworks.xstream.alias.CannotResolveClassException
//  -------------------------------

    /**
     * Simplify getMessage() output from XStream ConversionException
     * @param ce - ConversionException to analyse
     * @return string with details of error
     */
    public static String CEtoString(ConversionException ce){
        String msg =
            "XStream ConversionException at line: " + ce.get("line number")
            + "\n" + ce.get("message")
            + "\nPerhaps a missing jar? See log file.";
        return msg;
    }

    public static String getPropertiesVersion() {
        return propertiesVersion;
    }

    public static String getVERSION() {
        return VERSION;
    }
}
