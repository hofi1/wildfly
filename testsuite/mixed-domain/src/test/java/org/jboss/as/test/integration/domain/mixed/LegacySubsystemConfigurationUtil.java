/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.integration.domain.mixed;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.controller.Extension;
import org.jboss.as.controller.PathAddress;
import org.jboss.dmr.ModelNode;
import org.wildfly.build.configassembly.ConfigurationAssembler;
import org.wildfly.build.configassembly.SubsystemConfig;
import org.wildfly.build.configassembly.SubsystemInputStreamSources;
import org.wildfly.build.util.FileInputStreamSource;
import org.wildfly.build.util.InputStreamSource;
import org.wildfly.test.mixed.domain.TestParserUtils;

/**
 * Used by the domain adjuster to generate subsystem.xml files from existing templates for large/complex susbsystems.
 * Any extensions and socket binding groups must still be added manually.
 *
 *
 * @author Kabir Khan
 */
public class LegacySubsystemConfigurationUtil {

    private static final String SUBSYSTEM_OPEN = "<subsystem";
    private static final String SUBSYSTEM_CLOSE = "</subsystem>";
    private static final String TEST_NAMESPACE = "urn.org.jboss.test:1.0";

    final Extension extension;
    final String subsystemName;
    final String supplement;
    final String resourceName;
    final PathAddress profile;

    public LegacySubsystemConfigurationUtil(Extension extension, PathAddress profile, String subsystemName, String supplement, String resourceName) {
        this.extension = extension;
        this.subsystemName = subsystemName;
        this.supplement = supplement;
        this.resourceName = resourceName;
        this.profile = profile;
    }

    public List<ModelNode> getSubsystemOperations() throws Exception {
        File file = createAssembly();
        String subsystemXml = extractSubsystemXml(file);
        List<ModelNode> list = parseSubsystemXml(subsystemXml);
        for (ModelNode op : list) {
            PathAddress address = PathAddress.pathAddress(op.get(OP_ADDR));
            op.get(OP_ADDR).set(profile.append(address).toModelNode());
        }
        return list;
    }

    private List<ModelNode> parseSubsystemXml(String subsystemXml) throws XMLStreamException {
        return new TestParserUtils.Builder(extension, subsystemName, subsystemXml)
                .build()
                .parseOperations();
    }


    private String extractSubsystemXml(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        boolean inSusbsystem = false;
        for (String line : lines) {
            if (!inSusbsystem) {
                if (line.contains(SUBSYSTEM_OPEN)) {
                    inSusbsystem = true;
                    //This does not take into account things like <subsystem xmlns="..."/> but for simple subsystems
                    //like that this class should not be used anyway.
                    sb.append(line.substring(line.indexOf(SUBSYSTEM_OPEN)));
                }
            } else {
                if (!line.contains(SUBSYSTEM_CLOSE)) {
                    sb.append(line);
                } else {
                    sb.append(line, 0, line.indexOf(SUBSYSTEM_CLOSE) + SUBSYSTEM_CLOSE.length());
                    break;
                }
            }
        }
        return sb.toString();
    }

    private File createAssembly() throws IOException, XMLStreamException, URISyntaxException {
        final Map<String, Map<String, SubsystemConfig>> subsystemConfigs = new HashMap<>();
        final SubsystemInputStreamSources subsystemSources = createSubsystemInputStreamSources(subsystemConfigs);

        final File outputFile = createOutputFile();
        outputFile.delete();

        final URL url = this.getClass().getClassLoader().getResource("legacy-templates/test-template.xml");
        if (url == null) {
            throw new IllegalStateException("Can't find the template file");
        }
        final InputStreamSource template = new FileInputStreamSource(new File(url.toURI()));

        final ConfigurationAssembler assembler = new ConfigurationAssembler(subsystemSources, template, "server", subsystemConfigs, outputFile);
        assembler.assemble();
        return outputFile;
    }

    private SubsystemInputStreamSources createSubsystemInputStreamSources(Map<String, Map<String, SubsystemConfig>> subsystemConfigs) {
        final Map<String, URL> urls = new HashMap<>();
        addSubsystem(urls, subsystemConfigs, "messaging", "ha", "subsystem-templates/messaging.xml");

        return new SubsystemInputStreamSources() {
            @Override
            public InputStreamSource getInputStreamSource(String subsystem) {
                URL url = urls.get(subsystem);
                if (url == null) {
                    throw new IllegalArgumentException("No stream for " + subsystem);
                }
                return new InputStreamSource() {
                    @Override
                    public InputStream getInputStream() throws IOException {
                        return new BufferedInputStream(url.openStream());
                    }
                };
            }
        };
    }

    private void addSubsystem(Map<String, URL> urls, Map<String, Map<String, SubsystemConfig>> subsystemConfigs, String subsystem, String supplement, String resourceName) {
        URL url = this.getClass().getClassLoader().getResource(resourceName);

        if (url == null) {
            throw new IllegalArgumentException("Could not find " + resourceName);
        }

        urls.put(subsystem, url);
        Map<String, SubsystemConfig> config = Collections.singletonMap(subsystem, new SubsystemConfig(subsystem, supplement));
        subsystemConfigs.put("", config);
    }

    private File createOutputFile() throws IOException, URISyntaxException {
        final URL url = this.getClass().getProtectionDomain().getCodeSource().getLocation();
        final File target  = new File(url.toURI()).getParentFile();
        final File output = new File(target, subsystemName + "-legacy-xml");
        if (!output.exists()) {
            Files.createDirectories(Paths.get(output.getAbsolutePath()));
        }
        return new File(output, "test.xml");
    }}
