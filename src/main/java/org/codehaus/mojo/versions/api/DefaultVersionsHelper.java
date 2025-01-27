package org.codehaus.mojo.versions.api;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Settings;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.UnsupportedProtocolException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.codehaus.mojo.versions.PluginUpdatesDetails;
import org.codehaus.mojo.versions.Property;
import org.codehaus.mojo.versions.model.IgnoreVersion;
import org.codehaus.mojo.versions.model.Rule;
import org.codehaus.mojo.versions.model.RuleSet;
import org.codehaus.mojo.versions.model.io.xpp3.RuleXpp3Reader;
import org.codehaus.mojo.versions.ordering.VersionComparator;
import org.codehaus.mojo.versions.ordering.VersionComparators;
import org.codehaus.mojo.versions.utils.DependencyComparator;
import org.codehaus.mojo.versions.utils.PluginComparator;
import org.codehaus.mojo.versions.utils.RegexUtils;
import org.codehaus.mojo.versions.utils.VersionsExpressionEvaluator;
import org.codehaus.mojo.versions.utils.WagonUtils;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Helper class that provides common functionality required by both the mojos and the reports.
 *
 * @author Stephen Connolly
 * @since 1.0-alpha-3
 */
public class DefaultVersionsHelper
    implements VersionsHelper
{
    private static final String CLASSPATH_PROTOCOL = "classpath";

    private static final String TYPE_EXACT = "exact";

    private static final String TYPE_REGEX = "regex";

    private static final int LOOKUP_PARALLEL_THREADS = 5;

    /**
     * The artifact comparison rules to use.
     *
     * @since 1.0-alpha-3
     * @deprecated
     */
    private final RuleSet ruleSet;

    /**
     * The artifact metadata source to use.
     *
     * @since 1.0-alpha-3
     */
    private final ArtifactMetadataSource artifactMetadataSource;

    /**
     * The local repository to consult.
     *
     * @since 1.0-alpha-3
     */
    private final ArtifactRepository localRepository;

    /**
     * The remote artifact repositories to consult.
     *
     * @since 1.0-alpha-3
     */
    private final List<ArtifactRepository> remoteArtifactRepositories;

    /**
     * The remote plugin repositories to consult.
     *
     * @since 1.0-alpha-3
     */
    private final List<ArtifactRepository> remotePluginRepositories;

    private final RepositorySystem repositorySystem;

    /**
     * The {@link Log} to send log messages to.
     *
     * @since 1.0-alpha-3
     */
    private final Log log;

    /**
     * The maven session.
     *
     * @since 1.0-beta-1
     */
    private final MavenSession mavenSession;

    /**
     * The artifact resolver.
     *
     * @since 1.3
     */
    private final ArtifactResolver artifactResolver;

    private final MojoExecution mojoExecution;

    /**
     * A cache mapping artifacts to their best fitting rule, since looking up
     * this information can be quite costly.
     *
     * @since 2.12
     */
    private final Map<String, Rule> artifactBestFitRule = new HashMap<>();

    /**
     * Constructs a new {@link DefaultVersionsHelper}.
     *
     * @param repositorySystem           The repositorySystem.
     * @param artifactResolver           Artifact resolver
     * @param artifactMetadataSource     The artifact metadata source to use.
     * @param remoteArtifactRepositories The remote artifact repositories to consult.
     * @param remotePluginRepositories   The remote plugin repositories to consult.
     * @param localRepository            The local repository to consult.
     * @param wagonManager               The wagon manager (used if rules need to be retrieved).
     * @param settings                   The settings (used to provide proxy information to the wagon manager).
     * @param serverId                   The serverId hint for the wagon manager.
     * @param rulesUri                   The URL to retrieve the versioning rules from.
     * @param log                        The {@link org.apache.maven.plugin.logging.Log} to send log messages to.
     * @param mavenSession               The maven session information.
     * @throws MojoExecutionException if something goes wrong.
     * @since 1.0-alpha-3
     */
    @SuppressWarnings( "checkstyle:ParameterNumber" )
    public DefaultVersionsHelper( RepositorySystem repositorySystem, ArtifactResolver artifactResolver,
                                  ArtifactMetadataSource artifactMetadataSource,
                                  List<ArtifactRepository> remoteArtifactRepositories,
                                  List<ArtifactRepository> remotePluginRepositories, ArtifactRepository localRepository,
                                  WagonManager wagonManager, Settings settings, String serverId, String rulesUri,
                                  Log log, MavenSession mavenSession, MojoExecution mojoExecution )
        throws MojoExecutionException
    {
        this.repositorySystem = repositorySystem;
        this.artifactResolver = artifactResolver;
        this.mavenSession = mavenSession;
        this.mojoExecution = mojoExecution;
        this.ruleSet = loadRuleSet( serverId, settings, wagonManager, rulesUri, log );
        this.artifactMetadataSource = artifactMetadataSource;
        this.localRepository = localRepository;
        this.remoteArtifactRepositories = remoteArtifactRepositories;
        this.remotePluginRepositories = remotePluginRepositories;
        this.log = log;
    }

    @Deprecated
    private static RuleSet getRuleSet( Wagon wagon, String remoteURI )
        throws IOException, AuthorizationException, TransferFailedException, ResourceDoesNotExistException
    {
        File tempFile = File.createTempFile( "ruleset", ".xml" );
        try
        {
            wagon.get( remoteURI, tempFile );
            try ( InputStream is = new FileInputStream( tempFile ) )
            {
                return readRulesFromStream( is );
            }
        }
        finally
        {
            if ( !tempFile.delete() )
            {
                // maybe we can delete this later
                tempFile.deleteOnExit();
            }
        }
    }

    private static RuleSet readRulesFromStream( InputStream stream )
        throws IOException
    {
        RuleXpp3Reader reader = new RuleXpp3Reader();
        try ( BufferedInputStream bis = new BufferedInputStream( stream ) )
        {
            return reader.read( bis );
        }
        catch ( XmlPullParserException e )
        {
            throw new IOException( e );
        }
    }

    static boolean exactMatch( String wildcardRule, String value )
    {
        Pattern p = Pattern.compile( RegexUtils.convertWildcardsToRegex( wildcardRule, true ) );
        return p.matcher( value ).matches();
    }

    static boolean match( String wildcardRule, String value )
    {
        Pattern p = Pattern.compile( RegexUtils.convertWildcardsToRegex( wildcardRule, false ) );
        return p.matcher( value ).matches();
    }

    private static RuleSet loadRuleSet( String serverId, Settings settings, WagonManager wagonManager, String rulesUri,
                                        Log logger )
        throws MojoExecutionException
    {
        RuleSet ruleSet = new RuleSet();
        boolean rulesUriGiven = isRulesUriNotBlank( rulesUri );

        if ( rulesUriGiven )
        {
            RuleSet loadedRules;

            if ( isClasspathUri( rulesUri ) )
            {
                loadedRules = getRulesFromClasspath( rulesUri, logger );
            }
            else
            {
                loadedRules = getRulesViaWagon( rulesUri, logger, serverId, serverId, wagonManager,
                                                settings );
            }

            ruleSet.setIgnoreVersions( loadedRules.getIgnoreVersions() );
            ruleSet.setRules( loadedRules.getRules() );
        }

        return ruleSet;
    }

    private static RuleSet getRulesFromClasspath( String uri, Log logger )
        throws MojoExecutionException
    {
        logger.debug( "Going to load rules from \"" + uri + "\"" );

        String choppedUrl = uri.substring( CLASSPATH_PROTOCOL.length() + 3 );

        URL url = DefaultVersionsHelper.class.getResource( choppedUrl );

        if ( null == url )
        {
            String message = "Resource \"" + uri + "\" not found in classpath.";

            throw new MojoExecutionException( message );
        }

        try
        {
            RuleSet rules = readRulesFromStream( url.openStream() );
            logger.debug( "Loaded rules from \"" + uri + "\" successfully" );
            return rules;
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Could not load specified rules from " + uri, e );
        }
    }

    private static boolean isRulesUriNotBlank( String rulesUri )
    {
        return rulesUri != null && rulesUri.trim().length() != 0;
    }

    private static RuleSet getRulesViaWagon( String rulesUri, Log logger, String serverId, String id,
                                             WagonManager wagonManager, Settings settings )
        throws MojoExecutionException
    {
        RuleSet loadedRules = new RuleSet();

        int split = rulesUri.lastIndexOf( '/' );
        String baseUri = rulesUri;
        String fileUri = "";

        if ( split != -1 )
        {
            baseUri = rulesUri.substring( 0, split ) + '/';
            fileUri = split + 1 < rulesUri.length() ? rulesUri.substring( split + 1 ) : "";
        }

        try
        {
            Wagon wagon = WagonUtils.createWagon( serverId, baseUri, wagonManager, settings, logger );
            try
            {
                logger.debug( "Trying to load ruleset from file \"" + fileUri + "\" in " + baseUri );
                loadedRules = getRuleSet( wagon, fileUri );
            }
            finally
            {
                logger.debug( "Rule set loaded" );

                if ( wagon != null )
                {
                    try
                    {
                        wagon.disconnect();
                    }
                    catch ( ConnectionException e )
                    {
                        logger.warn( "Could not disconnect wagon!", e );
                    }
                }
            }
        }
        catch ( TransferFailedException e )
        {
            throw new MojoExecutionException( "Could not transfer rules from " + rulesUri, e );
        }
        catch ( AuthorizationException e )
        {
            throw new MojoExecutionException( "Authorization failure trying to load rules from " + rulesUri, e );
        }
        catch ( ResourceDoesNotExistException e )
        {
            throw new MojoExecutionException( "Could not load specified rules from " + rulesUri, e );
        }
        catch ( AuthenticationException e )
        {
            throw new MojoExecutionException( "Authentication failure trying to load rules from " + rulesUri, e );
        }
        catch ( UnsupportedProtocolException e )
        {
            throw new MojoExecutionException( "Unsupported protocol for " + rulesUri, e );
        }
        catch ( ConnectionException e )
        {
            throw new MojoExecutionException( "Could not establish connection to " + rulesUri, e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Could not load specified rules from " + rulesUri, e );
        }

        return loadedRules;
    }

    static boolean isClasspathUri( String uri )
    {
        return ( uri != null && uri.startsWith( CLASSPATH_PROTOCOL + ":" ) );
    }

    @Override
    public Log getLog()
    {
        return log;
    }

    @Override
    public ArtifactVersions lookupArtifactVersions( Artifact artifact, boolean usePluginRepositories )
        throws ArtifactMetadataRetrievalException
    {
        List remoteRepositories = usePluginRepositories ? remotePluginRepositories : remoteArtifactRepositories;
        final List<ArtifactVersion> versions =
            artifactMetadataSource.retrieveAvailableVersions( artifact, localRepository, remoteRepositories );
        final List<IgnoreVersion> ignoredVersions = getIgnoredVersions( artifact );
        if ( !ignoredVersions.isEmpty() )
        {
            if ( getLog().isDebugEnabled() )
            {
                getLog().debug( "Found ignored versions: " + showIgnoredVersions( ignoredVersions ) );
            }

            final Iterator<ArtifactVersion> i = versions.iterator();
            while ( i.hasNext() )
            {
                final String version = i.next().toString();
                for ( final IgnoreVersion ignoreVersion : ignoredVersions )
                {
                    if ( TYPE_REGEX.equals( ignoreVersion.getType() ) )
                    {
                        Pattern p = Pattern.compile( ignoreVersion.getVersion() );
                        if ( p.matcher( version ).matches() )
                        {
                            if ( getLog().isDebugEnabled() )
                            {
                                getLog().debug( "Version " + version + " for artifact "
                                                    + ArtifactUtils.versionlessKey( artifact )
                                                    + " found on ignore list: "
                                                    + ignoreVersion );
                            }
                            i.remove();
                            break;
                        }
                    }
                    else if ( TYPE_EXACT.equals( ignoreVersion.getType() ) )
                    {
                        if ( version.equals( ignoreVersion.getVersion() ) )
                        {
                            if ( getLog().isDebugEnabled() )
                            {
                                getLog().debug( "Version " + version + " for artifact "
                                                    + ArtifactUtils.versionlessKey( artifact )
                                                    + " found on ignore list: "
                                                    + ignoreVersion );
                            }
                            i.remove();
                            break;
                        }
                    }
                }
            }
        }
        return new ArtifactVersions( artifact, versions, getVersionComparator( artifact ) );
    }

    /**
     * Returns a list of versions which should not be considered when looking for updates.
     *
     * @param artifact The artifact
     * @return List of ignored version
     */
    private List<IgnoreVersion> getIgnoredVersions( Artifact artifact )
    {
        final List<IgnoreVersion> ret = new ArrayList<>();

        for ( final IgnoreVersion ignoreVersion : ruleSet.getIgnoreVersions() )
        {
            if ( !TYPE_EXACT.equals( ignoreVersion.getType() ) && !TYPE_REGEX.equals( ignoreVersion.getType() ) )
            {
                getLog().warn( "The type attribute '" + ignoreVersion.getType() + "' for global ignoreVersion["
                                   + ignoreVersion + "] is not valid." + " Please use either '" + TYPE_EXACT + "' or '"
                                   + TYPE_REGEX
                                   + "'." );
            }
            else
            {
                ret.add( ignoreVersion );
            }
        }

        final Rule rule = getBestFitRule( artifact.getGroupId(), artifact.getArtifactId() );

        if ( rule != null )
        {
            for ( IgnoreVersion ignoreVersion : rule.getIgnoreVersions() )
            {
                if ( !TYPE_EXACT.equals( ignoreVersion.getType() ) && !TYPE_REGEX.equals( ignoreVersion.getType() ) )
                {
                    getLog().warn( "The type attribute '" + ignoreVersion.getType() + "' for " + rule + " is not valid."
                                       + " Please use either '" + TYPE_EXACT + "' or '" + TYPE_REGEX + "'." );
                }
                else
                {
                    ret.add( ignoreVersion );
                }
            }
        }

        return ret;
    }

    /**
     * Pretty print a list of ignored versions.
     *
     * @param ignoredVersions A list of ignored versions
     * @return A String representation of the list
     */
    private String showIgnoredVersions( List<IgnoreVersion> ignoredVersions )
    {
        StringBuilder buf = new StringBuilder();
        Iterator<IgnoreVersion> iterator = ignoredVersions.iterator();
        while ( iterator.hasNext() )
        {
            IgnoreVersion ignoreVersion = iterator.next();
            buf.append( ignoreVersion );
            if ( iterator.hasNext() )
            {
                buf.append( ", " );
            }
        }
        return buf.toString();
    }

    @Override
    public void resolveArtifact( Artifact artifact, boolean usePluginRepositories )
        throws ArtifactResolutionException, ArtifactNotFoundException
    {
        List<ArtifactRepository> remoteRepositories =
            usePluginRepositories ? remotePluginRepositories : remoteArtifactRepositories;
        artifactResolver.resolve( artifact, remoteRepositories, localRepository );
    }

    @Override
    public VersionComparator getVersionComparator( Artifact artifact )
    {
        return getVersionComparator( artifact.getGroupId(), artifact.getArtifactId() );
    }

    @Override
    public VersionComparator getVersionComparator( String groupId, String artifactId )
    {
        Rule rule = getBestFitRule( groupId, artifactId );
        final String comparisonMethod = rule == null ? ruleSet.getComparisonMethod() : rule.getComparisonMethod();
        return VersionComparators.getVersionComparator( comparisonMethod );
    }

    /**
     * Find the rule, if any, which best fits the artifact details given.
     *
     * @param groupId    Group id of the artifact
     * @param artifactId Artifact id of the artifact
     * @return Rule which best describes the given artifact
     */
    protected Rule getBestFitRule( String groupId, String artifactId )
    {
        String groupArtifactId = groupId + ':' + artifactId;
        if ( artifactBestFitRule.containsKey( groupArtifactId ) )
        {
            return artifactBestFitRule.get( groupArtifactId );
        }

        Rule bestFit = null;
        final List<Rule> rules = ruleSet.getRules();
        int bestGroupIdScore = Integer.MAX_VALUE;
        int bestArtifactIdScore = Integer.MAX_VALUE;
        boolean exactGroupId = false;
        boolean exactArtifactId = false;
        for ( Rule rule : rules )
        {
            int groupIdScore = RegexUtils.getWildcardScore( rule.getGroupId() );
            if ( groupIdScore > bestGroupIdScore )
            {
                continue;
            }
            boolean exactMatch = exactMatch( rule.getGroupId(), groupId );
            boolean match = exactMatch || match( rule.getGroupId(), groupId );
            if ( !match || ( exactGroupId && !exactMatch ) )
            {
                continue;
            }
            if ( bestGroupIdScore > groupIdScore )
            {
                bestArtifactIdScore = Integer.MAX_VALUE;
                exactArtifactId = false;
            }
            bestGroupIdScore = groupIdScore;
            if ( exactMatch && !exactGroupId )
            {
                exactGroupId = true;
                bestArtifactIdScore = Integer.MAX_VALUE;
                exactArtifactId = false;
            }
            int artifactIdScore = RegexUtils.getWildcardScore( rule.getArtifactId() );
            if ( artifactIdScore > bestArtifactIdScore )
            {
                continue;
            }
            exactMatch = exactMatch( rule.getArtifactId(), artifactId );
            match = exactMatch || match( rule.getArtifactId(), artifactId );
            if ( !match || ( exactArtifactId && !exactMatch ) )
            {
                continue;
            }
            bestArtifactIdScore = artifactIdScore;
            if ( exactMatch && !exactArtifactId )
            {
                exactArtifactId = true;
            }
            bestFit = rule;
        }

        artifactBestFitRule.put( groupArtifactId, bestFit );
        return bestFit;
    }

    @Override
    public Artifact createPluginArtifact( String groupId, String artifactId, String version )
    {
        Plugin plugin = new Plugin();
        plugin.setGroupId( groupId );
        plugin.setArtifactId( artifactId );
        plugin.setVersion( StringUtils.isNotBlank( version ) ? version : "[0,]" );
        return repositorySystem.createPluginArtifact( plugin );
    }

    @Override
    public Artifact createDependencyArtifact( String groupId, String artifactId, String version, String type,
                                              String classifier, String scope, boolean optional )
    {
        Dependency dependency = new Dependency();
        dependency.setGroupId( groupId );
        dependency.setArtifactId( artifactId );
        dependency.setType( type );
        dependency.setClassifier( classifier );
        dependency.setScope( scope );
        dependency.setOptional( optional );
        dependency.setVersion( StringUtils.isNotBlank( version ) ? version : "[0,]" );
        return repositorySystem.createDependencyArtifact( dependency );
    }

    @Override
    public Artifact createDependencyArtifact( String groupId, String artifactId, String version, String type,
                                              String classifier, String scope )
    {
        return createDependencyArtifact( groupId, artifactId, version, type, classifier, scope, false );
    }

    @Override
    public Artifact createDependencyArtifact( Dependency dependency )
    {
        if ( StringUtils.isBlank( dependency.getVersion() ) )
        {
            dependency = dependency.clone();
            dependency.setVersion( "[,0]" );
        }

        return repositorySystem.createDependencyArtifact( dependency );
    }

    @Override
    public Set<Artifact> extractArtifacts( Collection<MavenProject> mavenProjects )
    {
        Set<Artifact> result = new HashSet<>();
        for ( MavenProject project : mavenProjects )
        {
            result.add( project.getArtifact() );
        }

        return result;
    }

    @Override
    public ArtifactVersion createArtifactVersion( String version )
    {
        return new DefaultArtifactVersion( version );
    }

    @Override
    public ArtifactVersions lookupArtifactUpdates( Artifact artifact, boolean allowSnapshots,
                                                   boolean usePluginRepositories )
        throws ArtifactMetadataRetrievalException
    {
        ArtifactVersions artifactVersions = lookupArtifactVersions( artifact, usePluginRepositories );

        artifactVersions.setIncludeSnapshots( allowSnapshots );

        return artifactVersions;
    }

    @Override
    public Map<Dependency, ArtifactVersions> lookupDependenciesUpdates( Set<Dependency> dependencies,
                                                                        boolean usePluginRepositories )
        throws ArtifactMetadataRetrievalException
    {
        // Create the request for details collection for parallel lookup...
        final List<Callable<DependencyArtifactVersions>> requestsForDetails =
            new ArrayList<>( dependencies.size() );
        for ( final Dependency dependency : dependencies )
        {
            requestsForDetails.add( new DependencyLookup( dependency, usePluginRepositories ) );
        }

        final Map<Dependency, ArtifactVersions> dependencyUpdates = new TreeMap<>( new DependencyComparator() );

        // Lookup details in parallel...
        final ExecutorService executor = Executors.newFixedThreadPool( LOOKUP_PARALLEL_THREADS );
        try
        {
            final List<Future<DependencyArtifactVersions>> responseForDetails =
                executor.invokeAll( requestsForDetails );

            // Construct the final results...
            for ( final Future<DependencyArtifactVersions> details : responseForDetails )
            {
                final DependencyArtifactVersions dav = details.get();
                dependencyUpdates.put( dav.getDependency(), dav.getArtifactVersions() );
            }
        }
        catch ( ExecutionException | InterruptedException ie )
        {
            throw new ArtifactMetadataRetrievalException( "Unable to acquire metadata for dependencies " + dependencies
                                                              + ": " + ie.getMessage(), ie, null );
        }
        finally
        {
            executor.shutdownNow();
        }
        return dependencyUpdates;
    }

    @Override
    public ArtifactVersions lookupDependencyUpdates( Dependency dependency, boolean usePluginRepositories )
        throws ArtifactMetadataRetrievalException
    {
        getLog().debug( "Checking "
                            + ArtifactUtils.versionlessKey( dependency.getGroupId(), dependency.getArtifactId() )
                            + " for updates newer than " + dependency.getVersion() );

        return lookupArtifactVersions( createDependencyArtifact( dependency ), usePluginRepositories );
    }

    @Override
    public Map<Plugin, PluginUpdatesDetails> lookupPluginsUpdates( Set<Plugin> plugins, boolean allowSnapshots )
        throws ArtifactMetadataRetrievalException
    {
        // Create the request for details collection for parallel lookup...
        List<Callable<PluginPluginUpdatesDetails>> requestsForDetails = new ArrayList<>( plugins.size() );
        for ( final Plugin plugin : plugins )
        {
            requestsForDetails.add( new PluginLookup( plugin, allowSnapshots ) );
        }

        Map<Plugin, PluginUpdatesDetails> pluginUpdates = new TreeMap<>( new PluginComparator() );

        // Lookup details in parallel...
        ExecutorService executor = Executors.newFixedThreadPool( LOOKUP_PARALLEL_THREADS );
        try
        {
            final List<Future<PluginPluginUpdatesDetails>> responseForDetails =
                executor.invokeAll( requestsForDetails );

            // Construct the final results...
            for ( final Future<PluginPluginUpdatesDetails> details : responseForDetails )
            {
                final PluginPluginUpdatesDetails pud = details.get();
                pluginUpdates.put( pud.getPlugin(), pud.getPluginUpdatesDetails() );
            }
        }
        catch ( ExecutionException | InterruptedException ie )
        {
            throw new ArtifactMetadataRetrievalException( "Unable to acquire metadata for plugins " + plugins + ": "
                                                              + ie.getMessage(), ie, null );
        }
        finally
        {
            executor.shutdownNow();
        }
        return pluginUpdates;
    }

    @Override
    public PluginUpdatesDetails lookupPluginUpdates( Plugin plugin, boolean allowSnapshots )
        throws ArtifactMetadataRetrievalException
    {
        String version = plugin.getVersion();
        version = version == null ? "LATEST" : version;
        getLog().debug( "Checking " + ArtifactUtils.versionlessKey( plugin.getGroupId(), plugin.getArtifactId() )
                            + " for updates newer than " + version );

        boolean includeSnapshots = allowSnapshots;

        final ArtifactVersions pluginArtifactVersions =
            lookupArtifactVersions( createPluginArtifact( plugin.getGroupId(), plugin.getArtifactId(), version ),
                                    true );

        Set<Dependency> pluginDependencies = new TreeSet<>( new DependencyComparator() );
        if ( plugin.getDependencies() != null )
        {
            pluginDependencies.addAll( plugin.getDependencies() );
        }
        Map<Dependency, ArtifactVersions> pluginDependencyDetails =
            lookupDependenciesUpdates( pluginDependencies, false );

        return new PluginUpdatesDetails( pluginArtifactVersions, pluginDependencyDetails, includeSnapshots );
    }

    @Override
    public ExpressionEvaluator getExpressionEvaluator( MavenProject project )
    {
        return new VersionsExpressionEvaluator( mavenSession, mojoExecution );
    }

    @Override
    public Map<Property, PropertyVersions> getVersionPropertiesMap( MavenProject project,
                                                                    Property[] propertyDefinitions,
                                                                    String includeProperties, String excludeProperties,
                                                                    boolean autoLinkItems )
        throws MojoExecutionException
    {
        Map<String, Property> properties = new HashMap<>();
        if ( propertyDefinitions != null )
        {
            for ( Property propertyDefinition : propertyDefinitions )
            {
                properties.put( propertyDefinition.getName(), propertyDefinition );
            }
        }
        Map<String, PropertyVersionsBuilder> builders = new HashMap<>();
        if ( autoLinkItems )
        {
            final PropertyVersionsBuilder[] propertyVersionsBuilders;
            try
            {
                propertyVersionsBuilders = PomHelper.getPropertyVersionsBuilders( this, project );
            }
            catch ( ExpressionEvaluationException | IOException e )
            {
                throw new MojoExecutionException( e.getMessage(), e );
            }

            for ( PropertyVersionsBuilder propertyVersionsBuilder : propertyVersionsBuilders )
            {
                final String name = propertyVersionsBuilder.getName();
                builders.put( name, propertyVersionsBuilder );
                if ( !properties.containsKey( name ) )
                {
                    final Property value = new Property( name );
                    getLog().debug( "Property ${" + name + "}: Adding inferred version range of "
                                        + propertyVersionsBuilder.getVersionRange() );
                    value.setVersion( propertyVersionsBuilder.getVersionRange() );
                    properties.put( name, value );
                }
            }
        }

        List<String> includePropertiesList = getSplittedProperties( includeProperties );
        List<String> excludePropertiesList = getSplittedProperties( excludeProperties );

        getLog().debug( "Searching for properties associated with builders" );
        Iterator<Property> i = properties.values().iterator();
        while ( i.hasNext() )
        {
            Property property = i.next();

            getLog().debug( "includePropertiesList:" + includePropertiesList + " property: " + property.getName() );
            getLog().debug( "excludePropertiesList:" + excludePropertiesList + " property: " + property.getName() );
            if ( !includePropertiesList.isEmpty() && !includePropertiesList.contains( property.getName() ) )
            {
                getLog().debug( "Skipping property ${" + property.getName() + "}" );
                i.remove();
            }
            else if ( !excludePropertiesList.isEmpty() && excludePropertiesList.contains( property.getName() ) )
            {
                getLog().debug( "Ignoring property ${" + property.getName() + "}" );
                i.remove();
            }
        }
        i = properties.values().iterator();
        Map<Property, PropertyVersions> propertyVersions = new LinkedHashMap<>( properties.size() );
        while ( i.hasNext() )
        {
            Property property = i.next();
            getLog().debug( "Property ${" + property.getName() + "}" );
            PropertyVersionsBuilder builder = builders.get( property.getName() );
            if ( builder == null || !builder.isAssociated() )
            {
                getLog().debug( "Property ${" + property.getName() + "}: Looks like this property is not "
                                    + "associated with any dependency..." );
                builder = new PropertyVersionsBuilder( null, property.getName(), this );
            }
            if ( !property.isAutoLinkDependencies() )
            {
                getLog().debug( "Property ${" + property.getName() + "}: Removing any autoLinkDependencies" );
                builder.clearAssociations();
            }
            Dependency[] dependencies = property.getDependencies();
            if ( dependencies != null )
            {
                for ( Dependency dependency : dependencies )
                {
                    getLog().debug( "Property ${" + property.getName() + "}: Adding association to " + dependency );
                    builder.addAssociation( this.createDependencyArtifact( dependency ), false );
                }
            }
            try
            {
                final PropertyVersions versions = builder.newPropertyVersions();
                if ( property.isAutoLinkDependencies() && StringUtils.isEmpty( property.getVersion() )
                    && !StringUtils.isEmpty( builder.getVersionRange() ) )
                {
                    getLog().debug( "Property ${" + property.getName() + "}: Adding inferred version range of "
                                        + builder.getVersionRange() );
                    property.setVersion( builder.getVersionRange() );
                }
                versions.setCurrentVersion( project.getProperties().getProperty( property.getName() ) );
                propertyVersions.put( property, versions );
            }
            catch ( ArtifactMetadataRetrievalException e )
            {
                throw new MojoExecutionException( e.getMessage(), e );
            }
        }
        return propertyVersions;
    }

    private List<String> getSplittedProperties( String commaSeparatedProperties )
    {
        List<String> propertiesList = Collections.emptyList();
        if ( StringUtils.isNotEmpty( commaSeparatedProperties ) )
        {
            String[] splittedProps = StringUtils.split( commaSeparatedProperties, "," );
            propertiesList = Arrays.asList( StringUtils.stripAll( splittedProps ) );
        }
        return propertiesList;
    }

    // This is a data container to hold the result of a Dependency lookup to its ArtifactVersions.
    private static class DependencyArtifactVersions
    {
        private final Dependency dependency;

        private final ArtifactVersions artifactVersions;

        DependencyArtifactVersions( final Dependency dependency, final ArtifactVersions artifactVersions )
        {
            this.dependency = dependency;
            this.artifactVersions = artifactVersions;
        }

        public Dependency getDependency()
        {
            return dependency;
        }

        public ArtifactVersions getArtifactVersions()
        {
            return artifactVersions;
        }
    }

    // This is a data container to hold the result of a Dependency lookup to its ArtifactVersions.
    private static class PluginPluginUpdatesDetails
    {
        private final Plugin plugin;

        private final PluginUpdatesDetails pluginUpdatesDetails;

        PluginPluginUpdatesDetails( final Plugin plugin, final PluginUpdatesDetails pluginUpdatesDetails )
        {
            this.plugin = plugin;
            this.pluginUpdatesDetails = pluginUpdatesDetails;
        }

        public Plugin getPlugin()
        {
            return plugin;
        }

        public PluginUpdatesDetails getPluginUpdatesDetails()
        {
            return pluginUpdatesDetails;
        }
    }

    // This Callable wraps lookupDependencyUpdates so that it can be run in parallel.
    private class DependencyLookup
        implements Callable<DependencyArtifactVersions>
    {
        private final Dependency dependency;

        private final boolean usePluginRepositories;

        DependencyLookup( final Dependency dependency, final boolean usePluginRepositories )
        {
            this.dependency = dependency;
            this.usePluginRepositories = usePluginRepositories;
        }

        public DependencyArtifactVersions call()
            throws Exception
        {
            return new DependencyArtifactVersions( dependency,
                                                   lookupDependencyUpdates( dependency, usePluginRepositories ) );
        }
    }

    // This Callable wraps lookupPluginUpdates so that it can be run in parallel.
    private class PluginLookup
        implements Callable<PluginPluginUpdatesDetails>
    {
        private final Plugin plugin;

        private final boolean allowSnapshots;

        PluginLookup( final Plugin plugin, final Boolean allowSnapshots )
        {
            this.plugin = plugin;
            this.allowSnapshots = allowSnapshots;
        }

        public PluginPluginUpdatesDetails call()
            throws Exception
        {
            return new PluginPluginUpdatesDetails( plugin, lookupPluginUpdates( plugin, allowSnapshots ) );
        }
    }

}
