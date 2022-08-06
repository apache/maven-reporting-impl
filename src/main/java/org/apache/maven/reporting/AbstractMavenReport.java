package org.apache.maven.reporting;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.sink.SinkFactory;
import org.apache.maven.doxia.site.decoration.DecorationModel;
import org.apache.maven.doxia.siterenderer.Renderer;
import org.apache.maven.doxia.siterenderer.RendererException;
import org.apache.maven.doxia.siterenderer.RenderingContext;
import org.apache.maven.doxia.siterenderer.SiteRenderingContext;
import org.apache.maven.doxia.siterenderer.sink.SiteRendererSink;
import org.apache.maven.doxia.tools.SiteTool;
import org.apache.maven.doxia.tools.SiteToolException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.WriterFactory;
import org.codehaus.plexus.util.ReaderFactory;

import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The basis for a Maven report which can be generated both as part of a site generation or
 * as a direct standalone goal invocation.
 * Both invocations are delegated to <code>abstract executeReport( Locale )</code> from:
 * <ul>
 * <li>Mojo's <code>execute()</code> method, see maven-plugin-api</li>
 * <li>MavenMultiPageReport's <code>generate( Sink, SinkFactory, Locale )</code>, see maven-reporting-api</li>
 * </ul>
 *
 * @author <a href="evenisse@apache.org">Emmanuel Venisse</a>
 * @since 2.0
 * @see #execute() <code>Mojo.execute()</code>, from maven-plugin-api
 * @see #generate(Sink, SinkFactory, Locale) <code>MavenMultiPageReport.generate( Sink, SinkFactory, Locale )</code>,
 *  from maven-reporting-api
 * @see #executeReport(Locale) <code>abstract executeReport( Locale )</code>
 */
public abstract class AbstractMavenReport
    extends AbstractMojo
    implements MavenMultiPageReport
{
    /**
     * The output directory for the report. Note that this parameter is only evaluated if the goal is run directly from
     * the command line. If the goal is run indirectly as part of a site generation, the output directory configured in
     * the Maven Site Plugin is used instead.
     */
    @Parameter( defaultValue = "${project.reporting.outputDirectory}", readonly = true, required = true )
    protected File outputDirectory;

    /**
     * The Maven Project.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    protected MavenProject project;

    /**
     * Specifies the input encoding.
     */
    @Parameter( property = "encoding", defaultValue = "${project.build.sourceEncoding}", readonly = true )
    private String inputEncoding;

    /**
     * Specifies the output encoding.
     */
    @Parameter( property = "outputEncoding", defaultValue = "${project.reporting.outputEncoding}", readonly = true )
    private String outputEncoding;

    /**
     * The local repository.
     */
    @Parameter( defaultValue = "${localRepository}", readonly = true, required = true )
    protected ArtifactRepository localRepository;

    /**
     * Remote repositories used for the project.
     */
    @Parameter( defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true )
    protected List<ArtifactRepository> remoteRepositories;

    /**
     * SiteTool.
     */
    @Component
    protected SiteTool siteTool;

    /**
     * Doxia Site Renderer component.
     */
    @Component
    protected Renderer siteRenderer;

    /** The current sink to use */
    private Sink sink;

    /** The sink factory to use */
    private SinkFactory sinkFactory;

    /** The current report output directory to use */
    private File reportOutputDirectory;

    /**
     * This method is called when the report generation is invoked directly as a standalone Mojo.
     *
     * @throws MojoExecutionException if an error occurs when generating the report
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute()
        throws MojoExecutionException
    {
        if ( !canGenerateReport() )
        {
            return;
        }

        File outputDirectory = new File( getOutputDirectory() );

        String filename = getOutputName() + ".html";

        Locale locale = Locale.getDefault();

        try
        {
            SiteRenderingContext siteContext = createSiteRenderingContext( locale );

            // copy resources
            getSiteRenderer().copyResources( siteContext, outputDirectory );

            // TODO Replace null with real value
            RenderingContext docRenderingContext = new RenderingContext( outputDirectory, filename, null );

            SiteRendererSink sink = new SiteRendererSink( docRenderingContext );

            generate( sink, null, locale );

            if ( !isExternalReport() ) // MSHARED-204: only render Doxia sink if not an external report
            {
                outputDirectory.mkdirs();

                try ( Writer writer =
                      new OutputStreamWriter( new FileOutputStream( new File( outputDirectory, filename ) ),
                                              getOutputEncoding() ) )
                {
                    // render report
                    getSiteRenderer().mergeDocumentIntoSite( writer, sink, siteContext );
                }
            }

            // copy generated resources also
            getSiteRenderer().copyResources( siteContext, outputDirectory );
        }
        catch ( RendererException | IOException | MavenReportException e )
        {
            throw new MojoExecutionException(
                "An error has occurred in " + getName( Locale.ENGLISH ) + " report generation.", e );
        }
    }

    private SiteRenderingContext createSiteRenderingContext( Locale locale )
        throws MavenReportException, IOException
    {
        DecorationModel decorationModel = new DecorationModel();

        Map<String, Object> templateProperties = new HashMap<>();
        // We tell the skin that we are rendering in standalone mode
        templateProperties.put( "standalone", Boolean.TRUE );
        templateProperties.put( "project", getProject() );
        templateProperties.put( "inputEncoding", getInputEncoding() );
        templateProperties.put( "outputEncoding", getOutputEncoding() );
        // Put any of the properties in directly into the Velocity context
        for ( Map.Entry<Object, Object> entry : getProject().getProperties().entrySet() )
        {
            templateProperties.put( (String) entry.getKey(), entry.getValue() );
        }

        SiteRenderingContext context;
        try
        {
           Artifact skinArtifact =
               siteTool.getSkinArtifactFromRepository( localRepository, remoteRepositories, decorationModel );

           getLog().info( buffer().a( "Rendering content with " ).strong( skinArtifact.getId()
               + " skin" ).a( '.' ).toString() );

            context = siteRenderer.createContextForSkin( skinArtifact, templateProperties, decorationModel,
                                                         project.getName(), locale );
        }
        catch ( SiteToolException e )
        {
            throw new MavenReportException( "Failed to retrieve skin artifact", e );
        }
        catch ( RendererException e )
        {
            throw new MavenReportException( "Failed to create context for skin", e );
        }

        // Generate static site
        context.setRootDirectory( project.getBasedir() );

        return context;
    }

    /**
     * Generate a report.
     *
     * @param sink the sink to use for the generation.
     * @param locale the wanted locale to generate the report, could be null.
     * @throws MavenReportException if any
     * @deprecated use {@link #generate(Sink, SinkFactory, Locale)} instead.
     */
    @Deprecated
    @Override
    public void generate( org.codehaus.doxia.sink.Sink sink, Locale locale )
        throws MavenReportException
    {
        generate( sink, null, locale );
    }

    /**
     * Generate a report.
     *
     * @param sink
     * @param locale
     * @throws MavenReportException
     * @deprecated use {@link #generate(Sink, SinkFactory, Locale)} instead.
     */
    @Deprecated
    public void generate( Sink sink, Locale locale )
        throws MavenReportException
    {
        generate( sink, null, locale );
    }

    /**
     * This method is called when the report generation is invoked by maven-site-plugin.
     *
     * @param sink
     * @param sinkFactory
     * @param locale
     * @throws MavenReportException
     */
    @Override
    public void generate( Sink sink, SinkFactory sinkFactory, Locale locale )
        throws MavenReportException
    {
        if ( !canGenerateReport() )
        {
            getLog().info( "This report cannot be generated as part of the current build. "
                           + "The report name should be referenced in this line of output." );
            return;
        }

        this.sink = sink;

        this.sinkFactory = sinkFactory;

        executeReport( locale );

        closeReport();
    }

    /**
     * @return CATEGORY_PROJECT_REPORTS
     */
    @Override
    public String getCategoryName()
    {
        return CATEGORY_PROJECT_REPORTS;
    }

    @Override
    public File getReportOutputDirectory()
    {
        if ( reportOutputDirectory == null )
        {
            reportOutputDirectory = new File( getOutputDirectory() );
        }

        return reportOutputDirectory;
    }

    @Override
    public void setReportOutputDirectory( File reportOutputDirectory )
    {
        this.reportOutputDirectory = reportOutputDirectory;
        this.outputDirectory = reportOutputDirectory;
    }

    protected String getOutputDirectory()
    {
        return outputDirectory.getAbsolutePath();
    }

    protected MavenProject getProject()
    {
        return project;
    }

    protected Renderer getSiteRenderer()
    {
        return siteRenderer;
    }

    /**
     * Gets the input files encoding.
     *
     * @return The input files encoding, never <code>null</code>.
     */
    protected String getInputEncoding()
    {
        return ( inputEncoding == null ) ? ReaderFactory.FILE_ENCODING : inputEncoding;
    }

    /**
     * Gets the effective reporting output files encoding.
     *
     * @return The effective reporting output file encoding, never <code>null</code>.
     */
    protected String getOutputEncoding()
    {
        return ( outputEncoding == null ) ? WriterFactory.UTF_8 : outputEncoding;
    }

    /**
     * Actions when closing the report.
     */
    protected void closeReport()
    {
        getSink().close();
    }

    /**
     * @return the sink used
     */
    public Sink getSink()
    {
        return sink;
    }

    /**
     * @return the sink factory used
     */
    public SinkFactory getSinkFactory()
    {
        return sinkFactory;
    }

    /**
     * @see org.apache.maven.reporting.MavenReport#isExternalReport()
     * @return {@code false} by default.
     */
    @Override
    public boolean isExternalReport()
    {
        return false;
    }

    @Override
    public boolean canGenerateReport()
    {
        return true;
    }

    /**
     * Execute the generation of the report.
     *
     * @param locale the wanted locale to return the report's description, could be <code>null</code>.
     * @throws MavenReportException if any
     */
    protected abstract void executeReport( Locale locale )
        throws MavenReportException;
}
