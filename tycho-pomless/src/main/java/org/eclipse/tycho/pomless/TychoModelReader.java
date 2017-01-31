/*******************************************************************************
 * Copyright (c) 2015 SAP SE and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    SAP SE - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.pomless;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.maven.model.Build;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.Organization;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.io.ModelParseException;
import org.apache.maven.model.io.ModelReader;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.sonatype.maven.polyglot.PolyglotModelManager;
import org.sonatype.maven.polyglot.PolyglotModelUtil;
import org.sonatype.maven.polyglot.io.ModelReaderSupport;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Tycho POM model reader. Deduces maven model artifactId and version from OSGi manifest
 * Bundle-SymbolicName and Bundle-Version headers or feature.xml id and version attributes. Assumes
 * parent pom is located in parent directory (from which groupId is inherited). Bundles with
 * Bundle-SymbolicName ending with ".tests" will be assigned packaging type "eclipse-test-plugin".
 */
@Component(role = ModelReader.class, hint = "tycho")
public class TychoModelReader extends ModelReaderSupport {

    private static final String BUNDLE_SYMBOLIC_NAME = "Bundle-SymbolicName";
    private static final String QUALIFIER_SUFFIX = ".qualifier";

    @Requirement
    private PolyglotModelManager polyglotModelManager;

    public TychoModelReader() {
    }

    @Override
    public Model read(Reader input, Map<String, ?> options) throws IOException, ModelParseException {
        File projectRoot = new File(PolyglotModelUtil.getLocation(options)).getParentFile();
        File manifestFile = new File(projectRoot, "META-INF/MANIFEST.MF");
        File featureXml = new File(projectRoot, "feature.xml");
        File categoryXml = new File(projectRoot, "category.xml");
        File productXml = getProductFile(projectRoot);
        if (manifestFile.isFile()) {
            return createPomFromManifest(manifestFile);
        } else if (featureXml.isFile()) {
            return createPomFromFeatureXml(featureXml);
        } else if (productXml != null) {
            return createPomFromProductXml(productXml);
        } else if (categoryXml.isFile()) {
            return createPomFromCategoryXml(categoryXml);
        } else {
            throw new IOException("Neither META-INF/MANIFEST.MF, feature.xml, .product nor category.xml file found in "
                    + projectRoot);
        }
    }

    private Model createPomFromManifest(File manifestFile) throws IOException, ModelParseException {
        Attributes headers = readManifestHeaders(manifestFile);
        String bundleSymbolicName = getBundleSymbolicName(headers, manifestFile);
        Model model = createModel();
        model.setParent(findParent(manifestFile.getParentFile().getParentFile()));
        // groupId is inherited from parent pom
        model.setArtifactId(bundleSymbolicName);
        String bundleVersion = getRequiredHeaderValue("Bundle-Version", headers, manifestFile);
        model.setVersion(getPomVersion(bundleVersion));
        model.setPackaging(getPackagingType(bundleSymbolicName));
        setLocation(model, manifestFile);
        String bundleName = getManifestAttributeValue(headers, "Bundle-Name", manifestFile);
        if (bundleName != null) {
            model.setName(bundleName);
        } else {
            model.setName(bundleSymbolicName);
        }
        String vendorName = getManifestAttributeValue(headers, "Bundle-Vendor", manifestFile);
        if (vendorName != null) {
            Organization organization = new Organization();
            organization.setName(vendorName);
            model.setOrganization(organization);
        }
        return model;
    }

    private static String getManifestAttributeValue(Attributes headers, String attributeName, File manifestFile)
            throws IOException {
        String location = headers.getValue("Bundle-Localization");
        if (location == null || location.isEmpty()) {
            location="OSGI-INF/l10n/bundle.properties";
        }
        String rawValue = headers.getValue(attributeName);
        if (rawValue != null && !rawValue.isEmpty()) {
            if (rawValue.startsWith("%")) {
                String key = rawValue.substring(1);
                //we always use the default here to have consistent build regardless of locale settings
                File l10nFile = new File(manifestFile.getParentFile().getParentFile(), location);
                if (l10nFile.exists()) {
                    Properties properties = new Properties();
                    try (InputStream stream = new FileInputStream(l10nFile)) {
                        properties.load(stream);
                    }
                    String translation = properties.getProperty(key);
                    if (translation != null && !translation.isEmpty()) {
                        return translation;
                    }
                }
                return key;
            }
        }
        return null;
    }

    private Model createPomFromFeatureXml(File featureXml) throws IOException, ModelParseException {
        Model model = createPomFromXmlFile(featureXml, "id", "version", "label", "provider-name");
        model.setPackaging("eclipse-feature");
        return model;
    }

    private Model createPomFromProductXml(File productXml) throws IOException, ModelParseException {
        Model model = createPomFromXmlFile(productXml, "uid", "version", "name", null);
        model.setPackaging("eclipse-repository");

        Build build = new Build();
        Plugin plugin = new Plugin();
        build.addPlugin(plugin);
        plugin.setArtifactId("tycho-p2-director-plugin");
        plugin.setGroupId("org.eclipse.tycho");

        PluginExecution materialize = new PluginExecution();
        materialize.setId("materialize-prodcuts");
        materialize.setGoals(Arrays.asList("materialize-products"));
        plugin.addExecution(materialize);
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom products = new Xpp3Dom("products");
        Xpp3Dom product = new Xpp3Dom("product");
        Xpp3Dom id = new Xpp3Dom("id");
        id.setValue(model.getArtifactId());
        config.addChild(products);
        products.addChild(product);
        product.addChild(id);
        materialize.setConfiguration(config);
        PluginExecution archive = new PluginExecution();
        archive.setId("archive-prodcuts");
        archive.setGoals(Arrays.asList("archive-products"));
        archive.setConfiguration(config);
        plugin.addExecution(archive);

        model.setBuild(build);
        return model;
    }

    private Model createPomFromXmlFile(File xmlFile, String idAttributeName, String versionAttributeName,
            String nameAttributeName, String vendorAttributeName) throws IOException, ModelParseException {
        Element root = getRootXmlElement(xmlFile);
        Model model = createModel();
        model.setParent(findParent(xmlFile.getParentFile()));
        String id = getXMLAttributeValue(root, idAttributeName);
        if (id == null) {
            throw new ModelParseException(String.format("missing or empty %s attribute in root element (%s)",
                    idAttributeName, xmlFile.getAbsolutePath()), -1, -1);
        }
        model.setArtifactId(id);
        model.setName(id);
        String version = getXMLAttributeValue(root, versionAttributeName);
        if (version == null) {
            throw new ModelParseException(String.format("missing or empty %s attribute in root element (%s)",
                    versionAttributeName, xmlFile.getAbsolutePath()), -1, -1);
        }
        model.setVersion(getPomVersion(version));
        if (nameAttributeName != null) {
            String name = getXMLAttributeValue(root, nameAttributeName);
            if (name != null) {
                model.setName(name);
            }
        }
        if (vendorAttributeName != null) {
            String vendor = getXMLAttributeValue(root, vendorAttributeName);
            if (vendor != null) {
                Organization organization = new Organization();
                organization.setName(vendor);
                model.setOrganization(organization);
            }
        }
        // groupId is inherited from parent pom
        setLocation(model, xmlFile);
        return model;
    }

    private static String getXMLAttributeValue(Element element, String attributeName) {
        Attr idNode = element.getAttributeNode(attributeName);
        if (idNode != null) {
            String value = idNode.getValue();
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private Model createPomFromCategoryXml(File categoryXml) throws ModelParseException, IOException {
        Model model = createModel();
        model.setPackaging("eclipse-repository");
        Parent parent = findParent(categoryXml.getParentFile());
        model.setParent(parent);
        String projectName = getProjectName(categoryXml.getParentFile());
        model.setArtifactId(projectName);
        model.setVersion(parent.getVersion());
        return model;
    }

    private String getProjectName(File projectRoot) throws IOException {
        File projectFile = new File(projectRoot, ".project");
        if (!projectFile.isFile()) {
            throw new IOException("No .project file could be found in project directory: " + projectRoot);
        }
        Element projectDescription = getRootXmlElement(projectFile);
        Node nameNode = projectDescription.getElementsByTagName("name").item(0);
        if (nameNode == null) {
            throw new IOException("No name element found in .project file " + projectFile);
        }
        return nameNode.getTextContent();
    }

    private Element getRootXmlElement(File xmlFile) throws IOException, ModelParseException {
        Document doc;
        try {
            DocumentBuilder parser = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            doc = parser.parse(xmlFile);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (SAXException e) {
            throw new ModelParseException(e.getMessage(), -1, -1);
        }
        return doc.getDocumentElement();
    }

    private Model createModel() {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        return model;
    }

    private String getBundleSymbolicName(Attributes headers, File manifestFile) throws ModelParseException {
        String symbolicName = getRequiredHeaderValue(BUNDLE_SYMBOLIC_NAME, headers, manifestFile);
        // strip off any directives/attributes
        int semicolonIndex = symbolicName.indexOf(';');
        if (semicolonIndex > 0) {
            symbolicName = symbolicName.substring(0, semicolonIndex);
        }
        return symbolicName;
    }

    private String getRequiredHeaderValue(String headerKey, Attributes headers, File manifestFile)
            throws ModelParseException {
        String value = headers.getValue(headerKey);
        if (value == null) {
            throw new ModelParseException("Required header " + headerKey + " missing in " + manifestFile, -1, -1);
        }
        return value;
    }

    private Attributes readManifestHeaders(File manifestFile) throws IOException {
        Manifest manifest = new Manifest();
        try (FileInputStream stream = new FileInputStream(manifestFile)) {
            manifest.read(stream);
        }
        return manifest.getMainAttributes();
    }

    private static String getPomVersion(String pdeVersion) {
        String pomVersion = pdeVersion;
        if (pdeVersion.endsWith(QUALIFIER_SUFFIX)) {
            pomVersion = pdeVersion.substring(0, pdeVersion.length() - QUALIFIER_SUFFIX.length()) + "-SNAPSHOT";
        }
        return pomVersion;
    }

    private String getPackagingType(String symbolicName) {
        // assume test bundles end with ".tests"
        if (symbolicName.endsWith(".tests")) {
            return "eclipse-test-plugin";
        } else {
            return "eclipse-plugin";
        }
    }

    public static File getProductFile(File projectRoot) {
        File[] productFiles = projectRoot
                .listFiles((File dir, String name) -> name.endsWith(".product") && !name.startsWith(".polyglot"));
        if (productFiles.length > 0 && productFiles[0].isFile()) {
            return productFiles[0];
        }
        return null;
    }

    Parent findParent(File projectRoot) throws ModelParseException, IOException {
        // assumption/limitation: parent pom must be physically located in
        // parent directory
        File parentPom = polyglotModelManager.locatePom(projectRoot.getParentFile());
        if (parentPom == null) {
            throw new FileNotFoundException("No parent pom file found in " + projectRoot.getParentFile());
        }
        Map<String, File> options = new HashMap<>(4);
        options.put(ModelProcessor.SOURCE, parentPom);
        ModelReader reader = polyglotModelManager.getReaderFor(options);
        Model parentModel = reader.read(parentPom, options);
        Parent parentReference = new Parent();
        String groupId = parentModel.getGroupId();
        if (groupId == null) {
            // must be inherited from grandparent
            groupId = parentModel.getParent().getGroupId();
        }
        parentReference.setGroupId(groupId);
        parentReference.setArtifactId(parentModel.getArtifactId());
        String version = parentModel.getVersion();
        if (version == null) {
            // must be inherited from grandparent
            version = parentModel.getParent().getVersion();
        }
        parentReference.setVersion(version);
        return parentReference;
    }

    private void setLocation(Model model, File modelSource) {
        InputSource inputSource = new InputSource();
        inputSource.setLocation(modelSource.toString());
        inputSource.setModelId(model.getParent().getGroupId() + ":" + model.getArtifactId() + ":" + model.getVersion());
        model.setLocation("", new InputLocation(0, 0, inputSource));
    }
}
