/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.properties;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.camel.Endpoint;
import org.apache.camel.api.management.ManagedAttribute;
import org.apache.camel.api.management.ManagedOperation;
import org.apache.camel.api.management.ManagedResource;
import org.apache.camel.spi.Metadata;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.LRUCacheFactory;
import org.apache.camel.util.FilePathResolver;
import org.apache.camel.util.ObjectHelper;

/**
 * The <a href="http://camel.apache.org/properties">Properties Component</a> allows you to use property placeholders when defining Endpoint URIs
 */
@Component("properties")
@ManagedResource(description = "Managed PropertiesComponent")
public class PropertiesComponent extends DefaultComponent implements org.apache.camel.spi.PropertiesComponent {

    /**
     *  Never check system properties.
     */
    public static final int SYSTEM_PROPERTIES_MODE_NEVER = 0;

    /**
     * Check system properties if not resolvable in the specified properties.
     */
    public static final int SYSTEM_PROPERTIES_MODE_FALLBACK = 1;

    /**
     * Check system properties variables) first, before trying the specified properties.
     * This allows system properties to override any other property source.
     * <p/>
     * This is the default.
     */
    public static final int SYSTEM_PROPERTIES_MODE_OVERRIDE = 2;

    /**
     *  Never check OS environment variables.
     */
    public static final int ENVIRONMENT_VARIABLES_MODE_NEVER = 0;

    /**
     * Check OS environment variables if not resolvable in the specified properties.
     * <p/>
     * This is the default.
     */
    public static final int ENVIRONMENT_VARIABLES_MODE_FALLBACK = 1;

    /**
     * Check OS environment variables first, before trying the specified properties.
     * This allows system properties to override any other property source.
     */
    public static final int ENVIRONMENT_VARIABLES_MODE_OVERRIDE = 2;

    /**
     * Key for stores special override properties that containers such as OSGi can store
     * in the OSGi service registry
     */
    public static final String OVERRIDE_PROPERTIES = PropertiesComponent.class.getName() + ".OverrideProperties";

    @SuppressWarnings("unchecked")
    private final Map<CacheKey, Properties> cacheMap = LRUCacheFactory.newLRUSoftCache(1000);
    private final Map<String, PropertiesFunction> functions = new LinkedHashMap<>();
    private PropertiesResolver propertiesResolver = new DefaultPropertiesResolver(this);
    private PropertiesParser propertiesParser = new DefaultPropertiesParser(this);
    private List<PropertiesLocation> locations = Collections.emptyList();

    private transient String propertyPrefixResolved;

    @Metadata
    private boolean ignoreMissingLocation;
    @Metadata
    private String encoding;
    @Metadata(defaultValue = "true")
    private boolean cache = true;
    @Metadata(label = "advanced")
    private String propertyPrefix;
    @Metadata(label = "advanced")
    private String propertySuffix;
    private transient String propertySuffixResolved;
    @Metadata(label = "advanced", defaultValue = "true")
    private boolean fallbackToUnaugmentedProperty = true;
    @Metadata(defaultValue = "true")
    private boolean defaultFallbackEnabled = true;
    @Metadata(label = "advanced", defaultValue = DEFAULT_PREFIX_TOKEN)
    private String prefixToken = DEFAULT_PREFIX_TOKEN;
    @Metadata(label = "advanced", defaultValue = DEFAULT_SUFFIX_TOKEN)
    private String suffixToken = DEFAULT_SUFFIX_TOKEN;
    @Metadata(label = "advanced")
    private Properties initialProperties;
    @Metadata(label = "advanced")
    private Properties overrideProperties;
    @Metadata(defaultValue = "" + SYSTEM_PROPERTIES_MODE_OVERRIDE, enums = "0,1,2")
    private int systemPropertiesMode = SYSTEM_PROPERTIES_MODE_OVERRIDE;
    @Metadata(defaultValue = "" + SYSTEM_PROPERTIES_MODE_FALLBACK, enums = "0,1,2")
    private int environmentVariableMode = ENVIRONMENT_VARIABLES_MODE_FALLBACK;

    public PropertiesComponent() {
        super();
        // include out of the box functions
        addFunction(new EnvPropertiesFunction());
        addFunction(new SysPropertiesFunction());
        addFunction(new ServicePropertiesFunction());
        addFunction(new ServiceHostPropertiesFunction());
        addFunction(new ServicePortPropertiesFunction());
    }

    public PropertiesComponent(String location) {
        this();
        setLocation(location);
    }

    public PropertiesComponent(String... locations) {
        this();
        setLocations(locations);
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        List<PropertiesLocation> paths = locations;

        Boolean ignoreMissingLocationLoc = getAndRemoveParameter(parameters, "ignoreMissingLocation", Boolean.class);
        if (ignoreMissingLocationLoc != null) {
            ignoreMissingLocation = ignoreMissingLocationLoc;
        }

        // override default locations
        String locations = getAndRemoveParameter(parameters, "locations", String.class);
        if (locations != null) {
            log.trace("Overriding default locations with location: {}", locations);
            paths = Arrays.stream(locations.split(",")).map(PropertiesLocation::new).collect(Collectors.toList());
        }

        String endpointUri = parseUri(remaining, paths);
        log.debug("Endpoint uri parsed as: {}", endpointUri);

        Endpoint delegate = getCamelContext().getEndpoint(endpointUri);
        PropertiesEndpoint answer = new PropertiesEndpoint(uri, delegate, this);

        setProperties(answer, parameters);
        return answer;
    }

    public String parseUri(String uri) throws Exception {
        return parseUri(uri, locations);
    }

    public String parseUri(String uri, String... locations) throws Exception {
        return parseUri(
            uri,
            locations != null
                ? Arrays.stream(locations).map(PropertiesLocation::new).collect(Collectors.toList())
                : Collections.emptyList());
    }

    public Properties loadProperties() throws Exception {
        return doLoadProperties(locations);
    }

    public Properties loadProperties(String... locations) throws Exception {
        if (locations != null) {
            return doLoadProperties(Arrays.stream(locations).map(PropertiesLocation::new).collect(Collectors.toList()));
        }
        return new Properties();
    }

    protected Properties doLoadProperties(List<PropertiesLocation> paths) throws Exception {
        Properties prop = new Properties();

        // use initial properties
        if (initialProperties != null) {
            prop.putAll(initialProperties);
        }

        // use locations
        if (paths != null) {
            // location may contain JVM system property or OS environment variables
            // so we need to parse those
            List<PropertiesLocation> locations = parseLocations(paths);

            // check cache first
            CacheKey key = new CacheKey(locations);
            Properties locationsProp = cache ? cacheMap.get(key) : null;
            if (locationsProp == null) {
                locationsProp = propertiesResolver.resolveProperties(getCamelContext(), ignoreMissingLocation, locations);
                if (cache) {
                    cacheMap.put(key, locationsProp);
                }
            }
            prop.putAll(locationsProp);
        }

        // use override properties
        if (overrideProperties != null) {
            // make a copy to avoid affecting the original properties
            Properties override = new Properties();
            override.putAll(prop);
            override.putAll(overrideProperties);
            prop = override;
        }

        return prop;
    }

    protected String parseUri(String uri, List<PropertiesLocation> paths) throws Exception {
        Properties prop = doLoadProperties(paths);

        // enclose tokens if missing
        if (!uri.contains(prefixToken) && !uri.startsWith(prefixToken)) {
            uri = prefixToken + uri;
        }
        if (!uri.contains(suffixToken) && !uri.endsWith(suffixToken)) {
            uri = uri + suffixToken;
        }

        log.trace("Parsing uri {} with properties: {}", uri, prop);
        
        if (propertiesParser instanceof AugmentedPropertyNameAwarePropertiesParser) {
            return ((AugmentedPropertyNameAwarePropertiesParser) propertiesParser).parseUri(
                uri,
                prop,
                prefixToken,
                suffixToken,
                propertyPrefixResolved,
                propertySuffixResolved,
                fallbackToUnaugmentedProperty,
                defaultFallbackEnabled);
        } else {
            return propertiesParser.parseUri(uri, prop, prefixToken, suffixToken);
        }
    }

    public List<PropertiesLocation> getLocations() {
        return locations;
    }

    /**
     * A list of locations to load properties.
     * This option will override any default locations and only use the locations from this option.
     */
    public void setLocations(List<PropertiesLocation> locations) {
        this.locations = Collections.unmodifiableList(locations);
    }

    /**
     * A list of locations to load properties.
     * This option will override any default locations and only use the locations from this option.
     */
    public void setLocations(String[] locationStrings) {
        List<PropertiesLocation> locations = new ArrayList<>();
        if (locationStrings != null) {
            for (String locationString : locationStrings) {
                locations.add(new PropertiesLocation(locationString));
            }
        }

        setLocations(locations);
    }

    /**
     * A list of locations to load properties.
     * This option will override any default locations and only use the locations from this option.
     */
    public void setLocations(Collection<String> locationStrings) {
        List<PropertiesLocation> locations = new ArrayList<>();
        if (locationStrings != null) {
            for (String locationString : locationStrings) {
                locations.add(new PropertiesLocation(locationString));
            }
        }

        setLocations(locations);
    }

    public void addLocation(String location) {
        if (location != null) {
            List<PropertiesLocation> newLocations = new ArrayList<>();
            for (String loc : location.split(",")) {
                newLocations.add(new PropertiesLocation(loc));
            }
            List<PropertiesLocation> current = getLocations();
            if (!current.isEmpty()) {
                newLocations.addAll(0, current);
            }
            setLocations(newLocations);
        }
    }

    /**
     * A list of locations to load properties. You can use comma to separate multiple locations.
     * This option will override any default locations and only use the locations from this option.
     */
    public void setLocation(String location) {
        if (location != null) {
            setLocations(location.split(","));
        }
    }

    @ManagedAttribute(description = "Encoding to use when loading properties file from the file system or classpath")
    public String getEncoding() {
        return encoding;
    }

    /**
     * Encoding to use when loading properties file from the file system or classpath.
     * <p/>
     * If no encoding has been set, then the properties files is loaded using ISO-8859-1 encoding (latin-1)
     * as documented by {@link java.util.Properties#load(java.io.InputStream)}
     */
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public PropertiesResolver getPropertiesResolver() {
        return propertiesResolver;
    }

    /**
     * To use a custom PropertiesResolver
     */
    public void setPropertiesResolver(PropertiesResolver propertiesResolver) {
        this.propertiesResolver = propertiesResolver;
    }

    public PropertiesParser getPropertiesParser() {
        return propertiesParser;
    }

    /**
     * To use a custom PropertiesParser
     */
    public void setPropertiesParser(PropertiesParser propertiesParser) {
        this.propertiesParser = propertiesParser;
    }

    @ManagedAttribute(description = "Whether to cache loaded properties")
    public boolean isCache() {
        return cache;
    }

    /**
     * Whether or not to cache loaded properties. The default value is true.
     */
    public void setCache(boolean cache) {
        this.cache = cache;
    }
    
    public String getPropertyPrefix() {
        return propertyPrefix;
    }

    /**
     * Optional prefix prepended to property names before resolution.
     */
    public void setPropertyPrefix(String propertyPrefix) {
        this.propertyPrefix = propertyPrefix;
        this.propertyPrefixResolved = propertyPrefix;
        if (ObjectHelper.isNotEmpty(this.propertyPrefix)) {
            this.propertyPrefixResolved = FilePathResolver.resolvePath(this.propertyPrefix);
        }
    }

    public String getPropertySuffix() {
        return propertySuffix;
    }

    /**
     * Optional suffix appended to property names before resolution.
     */
    public void setPropertySuffix(String propertySuffix) {
        this.propertySuffix = propertySuffix;
        this.propertySuffixResolved = propertySuffix;
        if (ObjectHelper.isNotEmpty(this.propertySuffix)) {
            this.propertySuffixResolved = FilePathResolver.resolvePath(this.propertySuffix);
        }
    }

    public boolean isFallbackToUnaugmentedProperty() {
        return fallbackToUnaugmentedProperty;
    }

    /**
     * If true, first attempt resolution of property name augmented with propertyPrefix and propertySuffix
     * before falling back the plain property name specified. If false, only the augmented property name is searched.
     */
    public void setFallbackToUnaugmentedProperty(boolean fallbackToUnaugmentedProperty) {
        this.fallbackToUnaugmentedProperty = fallbackToUnaugmentedProperty;
    }

    @ManagedAttribute(description = "Whether to support using fallback values if a property cannot be found")
    public boolean isDefaultFallbackEnabled() {
        return defaultFallbackEnabled;
    }

    /**
     * If false, the component does not attempt to find a default for the key by looking after the colon separator.
     */
    public void setDefaultFallbackEnabled(boolean defaultFallbackEnabled) {
        this.defaultFallbackEnabled = defaultFallbackEnabled;
    }

    @ManagedAttribute(description = "Ignore missing location")
    public boolean isIgnoreMissingLocation() {
        return ignoreMissingLocation;
    }

    /**
     * Whether to silently ignore if a location cannot be located, such as a properties file not found.
     */
    public void setIgnoreMissingLocation(boolean ignoreMissingLocation) {
        this.ignoreMissingLocation = ignoreMissingLocation;
    }

    @ManagedAttribute(description = "Prefix token")
    public String getPrefixToken() {
        return prefixToken;
    }

    /**
     * Sets the value of the prefix token used to identify properties to replace.  Setting a value of
     * {@code null} restores the default token (@link {@link #DEFAULT_PREFIX_TOKEN}).
     */
    public void setPrefixToken(String prefixToken) {
        if (prefixToken == null) {
            this.prefixToken = DEFAULT_PREFIX_TOKEN;
        } else {
            this.prefixToken = prefixToken;
        }
    }

    @ManagedAttribute(description = "Suffix token")
    public String getSuffixToken() {
        return suffixToken;
    }

    /**
     * Sets the value of the suffix token used to identify properties to replace.  Setting a value of
     * {@code null} restores the default token (@link {@link #DEFAULT_SUFFIX_TOKEN}).
     */
    public void setSuffixToken(String suffixToken) {
        if (suffixToken == null) {
            this.suffixToken = DEFAULT_SUFFIX_TOKEN;
        } else {
            this.suffixToken = suffixToken;
        }
    }

    public Properties getInitialProperties() {
        return initialProperties;
    }

    /**
     * Sets initial properties which will be used before any locations are resolved.
     *
     * @param initialProperties properties that are added first
     */
    public void setInitialProperties(Properties initialProperties) {
        this.initialProperties = initialProperties;
    }

    public Properties getOverrideProperties() {
        return overrideProperties;
    }

    /**
     * Sets a special list of override properties that take precedence
     * and will use first, if a property exist.
     *
     * @param overrideProperties properties that is used first
     */
    public void setOverrideProperties(Properties overrideProperties) {
        this.overrideProperties = overrideProperties;
    }

    /**
     * Gets the functions registered in this properties component.
     */
    public Map<String, PropertiesFunction> getFunctions() {
        return functions;
    }

    /**
     * Registers the {@link org.apache.camel.component.properties.PropertiesFunction} as a function to this component.
     */
    public void addFunction(PropertiesFunction function) {
        this.functions.put(function.getName(), function);
    }

    /**
     * Is there a {@link org.apache.camel.component.properties.PropertiesFunction} with the given name?
     */
    public boolean hasFunction(String name) {
        return functions.containsKey(name);
    }

    @ManagedAttribute(description = "System properties mode")
    public int getSystemPropertiesMode() {
        return systemPropertiesMode;
    }

    /**
     * Sets the system property (and environment variable) mode.
     *
     * The default mode (override) is to check system properties (and environment variables) first,
     * before trying the specified properties.
     * This allows system properties/environment variables to override any other property source.
     *
     * @see #SYSTEM_PROPERTIES_MODE_NEVER
     * @see #SYSTEM_PROPERTIES_MODE_FALLBACK
     * @see #SYSTEM_PROPERTIES_MODE_OVERRIDE
     */
    public void setSystemPropertiesMode(int systemPropertiesMode) {
        this.systemPropertiesMode = systemPropertiesMode;
    }

    @ManagedAttribute(description = "Environment variable mode")
    public int getEnvironmentVariableMode() {
        return environmentVariableMode;
    }

    /**
     * Sets the OS environment variables mode.
     *
     * The default mode (fallback) is to check OS environment variables,
     * if the property cannot be resolved from its sources first.
     * This allows environment variables as fallback values.
     *
     * @see #ENVIRONMENT_VARIABLES_MODE_NEVER
     * @see #ENVIRONMENT_VARIABLES_MODE_FALLBACK
     * @see #ENVIRONMENT_VARIABLES_MODE_OVERRIDE
     */
    public void setEnvironmentVariableMode(int environmentVariableMode) {
        this.environmentVariableMode = environmentVariableMode;
    }

    @Override
    public boolean isResolvePropertyPlaceholders() {
        // its chicken and egg, we cannot resolve placeholders on ourselves
        return false;
    }

    /**
     * Clears the cache
     */
    @ManagedOperation(description = "Clears the cache")
    public void clearCache() {
        this.cacheMap.clear();
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        if (systemPropertiesMode != SYSTEM_PROPERTIES_MODE_NEVER
                && systemPropertiesMode != SYSTEM_PROPERTIES_MODE_FALLBACK
                && systemPropertiesMode != SYSTEM_PROPERTIES_MODE_OVERRIDE) {
            throw new IllegalArgumentException("Option systemPropertiesMode has invalid value: " + systemPropertiesMode);
        }
        if (environmentVariableMode != ENVIRONMENT_VARIABLES_MODE_NEVER
                && environmentVariableMode != ENVIRONMENT_VARIABLES_MODE_FALLBACK
                && environmentVariableMode != ENVIRONMENT_VARIABLES_MODE_OVERRIDE) {
            throw new IllegalArgumentException("Option environmentVariableMode has invalid value: " + environmentVariableMode);
        }

        // inject the component to the parser
        if (propertiesParser instanceof DefaultPropertiesParser) {
            ((DefaultPropertiesParser) propertiesParser).setPropertiesComponent(this);
        }
    }

    @Override
    protected void doStop() throws Exception {
        cacheMap.clear();
        super.doStop();
    }

    private List<PropertiesLocation> parseLocations(List<PropertiesLocation> locations) {
        List<PropertiesLocation> answer = new ArrayList<>();

        for (PropertiesLocation location : locations) {
            log.trace("Parsing location: {}", location);

            try {
                String path = FilePathResolver.resolvePath(location.getPath());
                log.debug("Parsed location: {}", path);
                if (ObjectHelper.isNotEmpty(path)) {
                    answer.add(new PropertiesLocation(
                        location.getResolver(),
                        path,
                        location.isOptional())
                    );
                }
            } catch (IllegalArgumentException e) {
                if (!ignoreMissingLocation && !location.isOptional()) {
                    throw e;
                } else {
                    log.debug("Ignored missing location: {}", location);
                }
            }
        }

        // must return a not-null answer
        return answer;
    }

    /**
     * Key used in the locations cache
     */
    private static final class CacheKey implements Serializable {
        private static final long serialVersionUID = 1L;
        private final List<PropertiesLocation> locations;

        private CacheKey(List<PropertiesLocation> locations) {
            this.locations = new ArrayList<>(locations);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            CacheKey that = (CacheKey) o;

            return locations.equals(that.locations);
        }

        @Override
        public int hashCode() {
            return locations.hashCode();
        }

        @Override
        public String toString() {
            return "LocationKey[" + locations.toString() + "]";
        }
    }

}
