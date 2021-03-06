/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.extension.repository.aether.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Developer;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.codehaus.plexus.PlexusContainer;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactProperties;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.impl.ArtifactDescriptorReader;
import org.eclipse.aether.impl.VersionRangeResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.extension.DefaultExtensionAuthor;
import org.xwiki.extension.Extension;
import org.xwiki.extension.ExtensionDependency;
import org.xwiki.extension.ExtensionId;
import org.xwiki.extension.ExtensionLicense;
import org.xwiki.extension.ExtensionLicenseManager;
import org.xwiki.extension.ResolveException;
import org.xwiki.extension.repository.AbstractExtensionRepository;
import org.xwiki.extension.repository.ExtensionRepositoryDescriptor;
import org.xwiki.extension.repository.aether.internal.util.DefaultJavaNetProxySelector;
import org.xwiki.extension.repository.result.CollectionIterableResult;
import org.xwiki.extension.repository.result.IterableResult;
import org.xwiki.extension.version.Version;
import org.xwiki.extension.version.VersionConstraint;
import org.xwiki.extension.version.VersionRange;
import org.xwiki.extension.version.internal.DefaultVersion;
import org.xwiki.properties.ConverterManager;

/**
 * @version $Id$
 * @since 4.0M1
 */
public class AetherExtensionRepository extends AbstractExtensionRepository
{
    public static final String MPKEYPREFIX = "xwiki.extension.";

    public static final String MPNAME_NAME = "name";

    public static final String MPNAME_SUMMARY = "summary";

    public static final String MPNAME_WEBSITE = "website";

    public static final String MPNAME_FEATURES = "features";

    /**
     * Used to parse the version.
     */
    private static final GenericVersionScheme AETHERVERSIONSCHEME = new GenericVersionScheme();

    private static final Logger LOGGER = LoggerFactory.getLogger(AetherExtensionRepository.class);

    private transient PlexusContainer plexusContainer;

    private transient RemoteRepository remoteRepository;

    private transient ArtifactDescriptorReader mavenDescriptorReader;

    private transient VersionRangeResolver versionRangeResolver;

    private transient ConverterManager converter;

    private transient ExtensionLicenseManager licenseManager;

    private transient AetherExtensionRepositoryFactory repositoryFactory;

    private transient Method loadPomMethod;

    public AetherExtensionRepository(ExtensionRepositoryDescriptor repositoryDescriptor,
        AetherExtensionRepositoryFactory repositoryFactory, PlexusContainer plexusContainer,
        ComponentManager componentManager) throws Exception
    {
        super(repositoryDescriptor);

        this.repositoryFactory = repositoryFactory;
        this.plexusContainer = plexusContainer;

        RemoteRepository.Builder repositoryBuilder =
            new RemoteRepository.Builder(repositoryDescriptor.getId(), "default", repositoryDescriptor.getURI()
                .toString());

        // Authentication
        String username = getDescriptor().getProperty("auth.user");
        if (username != null) {
            AuthenticationBuilder authenticationBuilder = new AuthenticationBuilder();
            authenticationBuilder.addUsername(username);
            authenticationBuilder.addPassword(getDescriptor().getProperty("auth.password"));
            repositoryBuilder.setAuthentication(authenticationBuilder.build());
        }

        // Proxy
        try {
            repositoryBuilder.setProxy(DefaultJavaNetProxySelector.determineProxy(repositoryDescriptor.getURI()));
        } catch (Exception e) {
            LOGGER.warn("Unexpected exception when trying to find a proxy for [{}]", repositoryDescriptor.getURI());
        }

        this.remoteRepository = repositoryBuilder.build();

        this.converter = componentManager.getInstance(ConverterManager.class);
        this.licenseManager = componentManager.getInstance(ExtensionLicenseManager.class);

        this.versionRangeResolver = this.plexusContainer.lookup(VersionRangeResolver.class);

        this.mavenDescriptorReader = this.plexusContainer.lookup(ArtifactDescriptorReader.class);

        // FIXME: not very nice
        // * use a private method of a library we don't control is not the nicest thing. But it's a big and very
        // usefull method. A shame its not a bit more public.
        // * having to parse the pom.xml since we are supposed to support anything supported by AETHER is not
        // very clean either. But AETHER almost resolve nothing, not even the type of the artifact, we pretty
        // much get only dependencies and licenses.
        this.loadPomMethod =
            this.mavenDescriptorReader.getClass().getDeclaredMethod("loadPom", RepositorySystemSession.class,
                ArtifactDescriptorRequest.class, ArtifactDescriptorResult.class);
        this.loadPomMethod.setAccessible(true);
    }

    protected RepositorySystemSession createRepositorySystemSession()
    {
        return this.repositoryFactory.createRepositorySystemSession();
    }

    @Override
    public Extension resolve(ExtensionId extensionId) throws ResolveException
    {
        if (getDescriptor().getType().equals("maven") && this.mavenDescriptorReader != null) {
            return resolveMaven(extensionId);
        } else {
            // FIXME: impossible to resolve extension type as well as most of the information with pure Aether API
            throw new ResolveException("Unsupported");
        }
    }

    @Override
    public Extension resolve(ExtensionDependency extensionDependency) throws ResolveException
    {
        if (getDescriptor().getType().equals("maven") && this.mavenDescriptorReader != null) {
            return resolveMaven(extensionDependency);
        } else {
            // FIXME: impossible to resolve extension type as well as most of the information with pure Aether API
            throw new ResolveException("Unsupported");
        }
    }

    @Override
    public IterableResult<Version> resolveVersions(String id, int offset, int nb) throws ResolveException
    {
        Artifact artifact = AetherUtils.createArtifact(id, "(,)");

        List<org.eclipse.aether.version.Version> versions;
        try {
            versions = resolveVersions(artifact, createRepositorySystemSession());

            if (versions.isEmpty()) {
                throw new ResolveException("No versions available for id [" + id + "]");
            }
        } catch (VersionRangeResolutionException e) {
            throw new ResolveException("Failed to resolve versions for id [" + id + "]", e);
        }

        if (nb == 0 || offset >= versions.size()) {
            return new CollectionIterableResult<Version>(versions.size(), offset, Collections.<Version> emptyList());
        }

        int fromId = offset < 0 ? 0 : offset;
        int toId = offset + nb > versions.size() || nb < 0 ? versions.size() : offset + nb;

        List<Version> result = new ArrayList<Version>(toId - fromId);
        for (int i = fromId; i < toId; ++i) {
            result.add(new DefaultVersion(versions.get(i).toString()));
        }

        return new CollectionIterableResult<Version>(versions.size(), offset, result);
    }

    private org.eclipse.aether.version.Version resolveVersionConstraint(String id, VersionConstraint versionConstraint,
        RepositorySystemSession session) throws ResolveException
    {
        if (versionConstraint.getVersion() != null) {
            try {
                return AETHERVERSIONSCHEME.parseVersion(versionConstraint.getVersion().getValue());
            } catch (InvalidVersionSpecificationException e) {
                throw new ResolveException("Invalid version [" + versionConstraint.getVersion() + "]", e);
            }
        }

        List<org.eclipse.aether.version.Version> commonVersions = null;

        for (VersionRange range : versionConstraint.getRanges()) {
            List<org.eclipse.aether.version.Version> versions = resolveVersionRange(id, range, session);

            if (commonVersions == null) {
                commonVersions =
                    versionConstraint.getRanges().size() > 1 ? new ArrayList<org.eclipse.aether.version.Version>(
                        versions) : versions;
            } else {
                // Find commons versions between all the ranges of the constraint
                for (Iterator<org.eclipse.aether.version.Version> it = commonVersions.iterator(); it.hasNext();) {
                    org.eclipse.aether.version.Version version = it.next();
                    if (!versions.contains(version)) {
                        it.remove();
                    }
                }
            }
        }

        if (commonVersions.isEmpty()) {
            throw new ResolveException("No versions available for id [" + id + "] and version constraint ["
                + versionConstraint + "]");
        }

        return commonVersions.get(commonVersions.size() - 1);
    }

    private List<org.eclipse.aether.version.Version> resolveVersionRange(String id, VersionRange versionRange,
        RepositorySystemSession session) throws ResolveException
    {
        Artifact artifact = AetherUtils.createArtifact(id, versionRange.getValue());

        try {
            List<org.eclipse.aether.version.Version> versions = resolveVersions(artifact, session);

            if (versions.isEmpty()) {
                throw new ResolveException("No versions available for id [" + id + "] and version range ["
                    + versionRange + "]");
            }

            return versions;
        } catch (VersionRangeResolutionException e) {
            throw new ResolveException("Failed to resolve version range", e);
        }
    }

    private List<org.eclipse.aether.version.Version> resolveVersions(Artifact artifact, RepositorySystemSession session)
        throws VersionRangeResolutionException
    {
        VersionRangeRequest rangeRequest = new VersionRangeRequest();
        rangeRequest.setArtifact(artifact);
        rangeRequest.addRepository(this.remoteRepository);

        VersionRangeResult rangeResult = this.versionRangeResolver.resolveVersionRange(session, rangeRequest);

        return rangeResult.getVersions();
    }

    private String getProperty(Model model, String propertyName)
    {
        return model.getProperties().getProperty(MPKEYPREFIX + propertyName);
    }

    private String getPropertyString(Model model, String propertyName, String def)
    {
        return StringUtils.defaultString(getProperty(model, propertyName), def);
    }

    private AetherExtension resolveMaven(ExtensionDependency extensionDependency) throws ResolveException
    {
        RepositorySystemSession session = createRepositorySystemSession();

        Artifact artifact;
        String artifactExtension;
        if (extensionDependency instanceof AetherExtensionDependency) {
            artifact = ((AetherExtensionDependency) extensionDependency).getAetherDependency().getArtifact();
            artifactExtension =
                ((AetherExtensionDependency) extensionDependency).getAetherDependency().getArtifact().getExtension();
        } else {
            artifact =
                AetherUtils.createArtifact(extensionDependency.getId(), extensionDependency.getVersionConstraint()
                    .getValue());
            if (!extensionDependency.getVersionConstraint().getRanges().isEmpty()) {
                artifact =
                    artifact.setVersion(resolveVersionConstraint(extensionDependency.getId(),
                        extensionDependency.getVersionConstraint(), session).toString());
            }
            artifactExtension = null;
        }

        return resolveMaven(artifact, artifactExtension);
    }

    private AetherExtension resolveMaven(ExtensionId extensionId) throws ResolveException
    {
        Artifact artifact = AetherUtils.createArtifact(extensionId.getId(), extensionId.getVersion().getValue());

        return resolveMaven(artifact, null);
    }

    private AetherExtension resolveMaven(Artifact artifact, String artifactExtension) throws ResolveException
    {
        RepositorySystemSession session = createRepositorySystemSession();

        // Get Maven descriptor

        Model model;
        try {
            model = loadPom(artifact, session);
        } catch (Exception e) {
            throw new ResolveException("Failed to resolve artifact [" + artifact + "] descriptor", e);
        }

        if (model == null) {
            throw new ResolveException("Failed to resolve artifact [" + artifact + "] descriptor");
        }

        // Set type

        if (artifactExtension == null) {
            // Resolve extension from the pom packaging
            ArtifactType artifactType = session.getArtifactTypeRegistry().get(model.getPackaging());
            if (artifactType != null) {
                artifactExtension = artifactType.getExtension();
            } else {
                artifactExtension = model.getPackaging();
            }
        }

        artifact =
            new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(),
                artifactExtension, artifact.getVersion());

        AetherExtension extension = new AetherExtension(artifact, model, this, this.plexusContainer);

        extension.setName(getPropertyString(model, MPNAME_NAME, model.getName()));
        extension.setSummary(getPropertyString(model, MPNAME_SUMMARY, model.getDescription()));
        extension.setWebsite(getPropertyString(model, MPNAME_WEBSITE, model.getUrl()));

        // authors
        for (Developer developer : model.getDevelopers()) {
            URL authorURL = null;
            if (developer.getUrl() != null) {
                try {
                    authorURL = new URL(developer.getUrl());
                } catch (MalformedURLException e) {
                    // TODO: log ?
                }
            }

            extension.addAuthor(new DefaultExtensionAuthor(StringUtils.defaultIfBlank(developer.getName(),
                developer.getId()), authorURL));
        }

        // licenses
        for (License license : model.getLicenses()) {
            extension.addLicense(getExtensionLicense(license));
        }

        // features
        String featuresString = getProperty(model, MPNAME_FEATURES);
        if (StringUtils.isNotBlank(featuresString)) {
            featuresString = featuresString.replaceAll("[\r\n]", "");
            extension.setFeatures(this.converter.<Collection<String>> convert(List.class, featuresString));
        }

        // dependencies
        try {
            ArtifactTypeRegistry stereotypes = session.getArtifactTypeRegistry();

            for (org.apache.maven.model.Dependency mavenDependency : model.getDependencies()) {
                if (!mavenDependency.isOptional()
                    && (mavenDependency.getScope().equals("compile") || mavenDependency.getScope().equals("runtime"))) {
                    extension.addDependency(new AetherExtensionDependency(
                        convertToAether(mavenDependency, stereotypes), mavenDependency));
                }
            }
        } catch (Exception e) {
            throw new ResolveException("Failed to resolve dependencies", e);
        }

        return extension;
    }

    private Dependency convertToAether(org.apache.maven.model.Dependency dependency, ArtifactTypeRegistry stereotypes)
    {
        ArtifactType stereotype = stereotypes.get(dependency.getType());
        if (stereotype == null) {
            stereotype = new DefaultArtifactType(dependency.getType());
        }

        boolean system = dependency.getSystemPath() != null && dependency.getSystemPath().length() > 0;

        Map<String, String> props = null;
        if (system) {
            props = Collections.singletonMap(ArtifactProperties.LOCAL_PATH, dependency.getSystemPath());
        }

        Artifact artifact =
            new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(), dependency.getClassifier(), null,
                dependency.getVersion(), props, stereotype);

        List<Exclusion> exclusions = new ArrayList<Exclusion>(dependency.getExclusions().size());
        for (org.apache.maven.model.Exclusion exclusion : dependency.getExclusions()) {
            exclusions.add(convert(exclusion));
        }

        Dependency result = new Dependency(artifact, dependency.getScope(), dependency.isOptional(), exclusions);

        return result;
    }

    private Exclusion convert(org.apache.maven.model.Exclusion exclusion)
    {
        return new Exclusion(exclusion.getGroupId(), exclusion.getArtifactId(), "*", "*");
    }

    // TODO: download custom licenses content
    private ExtensionLicense getExtensionLicense(License license)
    {
        if (license.getName() == null) {
            return new ExtensionLicense("noname", null);
        }

        return createLicenseByName(license.getName());
    }

    private ExtensionLicense createLicenseByName(String name)
    {
        ExtensionLicense extensionLicense = this.licenseManager.getLicense(name);

        return extensionLicense != null ? extensionLicense : new ExtensionLicense(name, null);

    }

    private Model loadPom(Artifact artifact, RepositorySystemSession session) throws IllegalArgumentException,
        IllegalAccessException, InvocationTargetException
    {
        ArtifactDescriptorRequest artifactDescriptorRequest = new ArtifactDescriptorRequest();
        artifactDescriptorRequest.setArtifact(artifact);
        artifactDescriptorRequest.addRepository(this.remoteRepository);

        ArtifactDescriptorResult artifactDescriptorResult = new ArtifactDescriptorResult(artifactDescriptorRequest);

        return (Model) this.loadPomMethod.invoke(this.mavenDescriptorReader, session, artifactDescriptorRequest,
            artifactDescriptorResult);
    }

    protected RemoteRepository getRemoteRepository()
    {
        return this.remoteRepository;
    }
}
