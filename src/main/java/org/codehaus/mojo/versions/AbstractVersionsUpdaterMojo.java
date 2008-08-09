package org.codehaus.mojo.versions;

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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Abstract base class for Versions Mojos.
 *
 * @author <a href="mailto:stephen.alan.connolly@gmail.com">Stephen Connolly</a>
 */
public abstract class AbstractVersionsUpdaterMojo
    extends AbstractMojo
{

// ------------------------------ FIELDS ------------------------------

    /**
     * The encoding used for the pom file.
     */
    private static final String POM_ENCODING = "UTF-8";

    /**
     * @component
     */
    protected org.apache.maven.artifact.factory.ArtifactFactory artifactFactory;

    /**
     * @component
     */
    protected org.apache.maven.artifact.resolver.ArtifactResolver resolver;

    /**
     * @parameter expression="${localRepository}"
     */
    protected org.apache.maven.artifact.repository.ArtifactRepository localRepository;

    /**
     * @parameter expression="${project.remoteArtifactRepositories}"
     */
    protected List remoteRepositories;

    /**
     * The artifact metadata source to use.
     *
     * @component
     * @required
     * @readonly
     */
    protected ArtifactMetadataSource artifactMetadataSource;

    /**
     * The properties to update and the artifact coordinates that they are to be updated from.
     *
     * @parameter
     */
    protected LinkItem[] linkItems;

    /**
     * A comma separated list of properties to update.
     *
     * @parameter expression="${includeProperties}"
     */
    protected String includeProperties = null;

    /**
     * A comma separated list of properties to not update.
     *
     * @parameter expression="${excludeProperties}"
     */
    protected String excludeProperties = null;

    /**
     * The versioning rule to use when comparing versions. Valid values are <code>maven</code> which is the default or
     * <code>numeric</code> which will handle long version numbers provided all components are numeric.
     *
     * @parameter expression="${comparisonMethod}"
     */
    protected String comparisonMethod = null;

    /**
     * Version specification to control artifact resolution.
     *
     * @parameter expression="${parentVersion}"
     */
    protected String parentVersion = null;

    /**
     * Version specification to control artifact resolution.
     *
     * @parameter expression="${allowSnapshots}"
     */
    protected Boolean allowSnapshots = Boolean.TRUE;

    /**
     * @parameter expression="${reactorProjects}"
     * @required
     * @readonly
     */
    protected List reactorProjects;

    /**
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * The string that identifies a snapshot.
     */
    private static final String SNAPSHOT = "-SNAPSHOT";

// --------------------- GETTER / SETTER METHODS ---------------------

    /**
     * Getter for property 'project'.
     *
     * @return Value for property 'project'.
     */
    public MavenProject getProject()
    {
        return project;
    }

    /**
     * Setter for property 'project'.
     *
     * @param project Value to set for property 'project'.
     */
    public void setProject( MavenProject project )
    {
        this.project = project;
    }

// ------------------------ INTERFACE METHODS ------------------------

// --------------------- Interface Mojo ---------------------

    /**
     * {@inheritDoc}
     */
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        File outFile = project.getFile();
        process( outFile );
    }

// -------------------------- OTHER METHODS --------------------------

    /**
     * Finds the latest version of the specified artifact that matches the version range.
     *
     * @param artifact     The artifact.
     * @param versionRange The version range.
     * @return The latest version of the specified artifact that matches the specified version range or
     *         <code>null</code> if no matching version could be found.
     * @throws MojoExecutionException If the artifact metadata could not be found.
     */
    protected ArtifactVersion findLatestVersion( Artifact artifact, VersionRange versionRange )
        throws MojoExecutionException
    {
        final List versions;
        try
        {
            versions = artifactMetadataSource
                .retrieveAvailableVersions( artifact, localRepository, remoteRepositories );
        }
        catch ( ArtifactMetadataRetrievalException e )
        {
            throw new MojoExecutionException( "Could not retrieve metadata for " + artifact, e );
        }

        getLog().debug( artifact.toString() + " has versions " + versions.toString() );

        final Comparator versionComparator = getVersionComparator();
        ArtifactVersion artifactVersion = null;
        for ( Iterator j = versions.iterator(); j.hasNext(); )
        {
            ArtifactVersion ver = (ArtifactVersion) j.next();
            if ( Boolean.FALSE.equals( allowSnapshots ) && ver.toString().endsWith( SNAPSHOT ) )
            {
                // not this version as it's a snapshot and we've been told no snapshots.
                continue;
            }
            if ( versionRange.containsVersion( ver ) )
            {
                // valid - check if it is greater than the currently matched version
                if ( artifactVersion == null || versionComparator.compare( ver, artifactVersion ) > 0 )
                {
                    artifactVersion = ver;
                }
            }
        }
        if ( artifactVersion == null )
        {
            getLog().warn( "Could not find any version of " + artifact + " matching " + versionRange );
        }
        return artifactVersion;
    }

    /**
     * Returns the version comparator to use.
     *
     * @return the version comparator to use.
     */
    protected Comparator getVersionComparator()
    {
        if ( "numeric".equalsIgnoreCase( comparisonMethod ) )
        {
            return new NumericVersionComparator();
        }
        return new MavenVersionComparator();
    }

    /**
     * Gets the property value that is defined in the pom. This is an extension point to allow updating a file
     * external to the reactor.
     *
     * @param pom      The pom.
     * @param property The property.
     * @return The value as defined in the pom or <code>null</code> if not defined.
     */
    protected String getPropertyValue( StringBuffer pom, String property )
    {
        return project.getProperties().getProperty( property );
    }

    /**
     * Processes the specified file. This is an extension point to allow updating a file external to the reactor.
     *
     * @param outFile The file to process.
     * @throws MojoExecutionException If things go wrong.
     * @throws MojoFailureException   If things go wrong.
     */
    protected void process( File outFile )
        throws MojoExecutionException, MojoFailureException
    {
        BufferedInputStream reader;
        try
        {
            reader = new BufferedInputStream( new FileInputStream( outFile ) );

            byte[] content = new byte[(int) outFile.length()];
            StringBuffer pom = new StringBuffer( content.length );
            try
            {
                int length = reader.read( content, 0, content.length );
                pom.append( new String( content, 0, length, POM_ENCODING ) );
            }
            finally
            {
                reader.close();
            }

            if ( update( pom ) )
            {
                OutputStream out = new BufferedOutputStream( new FileOutputStream( outFile ) );
                out.write( pom.toString().getBytes( POM_ENCODING ) );
                out.close();
            }
        }
        catch ( IOException e )
        {
            getLog().error( e );
        }
    }

    /**
     * Updates the pom.
     *
     * @param pom The pom as a StringBuffer.
     * @return <code>true</code> if the pom has been modified.
     * @throws MojoExecutionException If things go wrong.
     * @throws MojoFailureException   If things go wrong.
     */
    protected abstract boolean update( StringBuffer pom )
        throws MojoExecutionException, MojoFailureException;

    /**
     * Returns <code>true</code> if the update should be applied.
     *
     * @param artifact       The artifact.
     * @param currentVersion The current version of the artifact.
     * @param updateVersion  The proposed new version of the artifact.
     * @return <code>true</code> if the update should be applied.
     */
    protected boolean shouldApplyUpdate( Artifact artifact, String currentVersion, ArtifactVersion updateVersion )
    {
        getLog().debug( "Proposal is to update from " + currentVersion + " to " + updateVersion );

        artifact.setVersion( updateVersion.toString() );
        try
        {
            resolver.resolveAlways( artifact, remoteRepositories, localRepository );
        }
        catch ( ArtifactResolutionException e )
        {
            getLog().warn( "Not updating version: could not resolve " + artifact.toString(), e );
            return false;
        }
        catch ( ArtifactNotFoundException e )
        {
            getLog().warn( "Not updating version: could not find " + artifact.toString(), e );
            return false;
        }

        if ( currentVersion.equals( updateVersion.toString() ) )
        {
            getLog().info( "Not updating version: current version already up to date." );
            return false;
        }
        return true;
    }

// -------------------------- INNER CLASSES --------------------------

    /**
     * A comparator which will compare all segments of a dot separated version string as numbers if possible,
     * i.e. 1.3.34 &gt; 1.3.9 and 1.3.4.3.2.34 &gt; 1.3.4.3.2.9 and 1.3.4.3.2.34 &gt; 1.3.4.3.2.34-SNAPSHOT
     */
    static class NumericVersionComparator
        implements Comparator
    {

        /**
         * {@inheritDoc}
         */
        public int compare( Object o1, Object o2 )
        {
            String v1 = o1.toString();
            String v2 = o2.toString();
            StringTokenizer tok1 = new StringTokenizer( v1, "." );
            StringTokenizer tok2 = new StringTokenizer( v2, "." );
            while ( tok1.hasMoreTokens() && tok2.hasMoreTokens() )
            {
                String p1 = tok1.nextToken();
                String p2 = tok2.nextToken();
                String q1 = null;
                String q2 = null;
                if ( p1.indexOf( '-' ) >= 0 )
                {
                    int index = p1.indexOf( '-' );
                    p1 = p1.substring( 0, index );
                    q1 = p1.substring( index );
                }
                if ( p2.indexOf( '-' ) >= 0 )
                {
                    int index = p2.indexOf( '-' );
                    p2 = p2.substring( 0, index );
                    q2 = p2.substring( index );
                }
                Integer n1 = null;
                Integer n2 = null;
                try
                {
                    n1 = Integer.valueOf( p1 );
                    n2 = Integer.valueOf( p2 );
                }
                catch ( NumberFormatException e )
                {
                    // ignore
                }
                if ( n1 != null && n2 != null )
                {
                    int result = n1.compareTo( n2 );
                    if ( result != 0 )
                    {
                        return result;
                    }
                }
                else
                {
                    int result = p1.compareTo( p2 );
                    if ( result != 0 )
                    {
                        return result;
                    }
                }
                if ( q1 != null && q2 != null )
                {
                    return q1.compareTo( q2 );
                }
                if ( q1 != null )
                {
                    return -1;
                }
                if ( q2 != null )
                {
                    return +1;
                }
            }
            if ( tok1.hasMoreTokens() )
            {
                return -1;
            }
            if ( tok2.hasMoreTokens() )
            {
                return +1;
            }
            return 0;
        }

    }

    /**
     * A comparator which uses Maven's version rules, i.e. 1.3.34 &gt; 1.3.9 but 1.3.4.3.2.34 &lt; 1.3.4.3.2.9.
     */
    private static class MavenVersionComparator
        implements Comparator
    {

        /**
         * {@inheritDoc}
         */
        public int compare( Object o1, Object o2 )
        {
            return ( (ArtifactVersion) o1 ).compareTo( (ArtifactVersion) o2 );
        }

    }

}