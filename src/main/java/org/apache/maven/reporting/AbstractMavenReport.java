/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.reporting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.sink.SinkFactory;
import org.apache.maven.doxia.site.SiteModel;
import org.apache.maven.doxia.siterenderer.DocumentRenderingContext;
import org.apache.maven.doxia.siterenderer.Renderer;
import org.apache.maven.doxia.siterenderer.RendererException;
import org.apache.maven.doxia.siterenderer.SiteRenderingContext;
import org.apache.maven.doxia.siterenderer.sink.SiteRendererSink;
import org.apache.maven.doxia.tools.SiteTool;
import org.apache.maven.doxia.tools.SiteToolException;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Reporting;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.WriterFactory;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.PathTool;
import org.codehaus.plexus.util.ReaderFactory;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;

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
public abstract class AbstractMavenReport extends AbstractMojo implements MavenMultiPageReport {
    /**
     * The shared output directory for the report. Note that this parameter is only evaluated if the goal is run
     * directly from the command line. If the goal is run indirectly as part of a site generation, the shared
     * output directory configured in the
     * <a href="https://maven.apache.org/plugins/maven-site-plugin/site-mojo.html#outputDirectory">Maven Site Plugin</a>
     * is used instead.
     *<p>
     * A plugin may use any subdirectory structure (either using a hard-coded name or, ideally, an additional
     * user-defined mojo parameter with a default value) to generate multi-page reports or external reports with the
     * main output file (entry point) denoted by {@link #getOutputPath}.
     */
    @Parameter(defaultValue = "${project.build.directory}/reports", required = true)
    protected File outputDirectory;

    /**
     * The Maven Project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * The mojo execution
     */
    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    protected MojoExecution mojoExecution;

    /**
     * The reactor projects.
     */
    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;

    /**
     * Specifies the input encoding.
     */
    @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}", readonly = true)
    private String inputEncoding;

    /**
     * Specifies the output encoding.
     */
    @Parameter(property = "outputEncoding", defaultValue = "${project.reporting.outputEncoding}", readonly = true)
    private String outputEncoding;

    /**
     * The repository system session.
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    protected RepositorySystemSession repoSession;

    /**
     * Remote project repositories used for the project.
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    protected List<RemoteRepository> remoteProjectRepositories;

    /**
     * Directory containing the <code>site.xml</code> file.
     */
    @Parameter(defaultValue = "${basedir}/src/site")
    protected File siteDirectory;

    /**
     * The locale to use when the report generation is invoked directly as a standalone Mojo.
     *
     * @see SiteTool#DEFAULT_LOCALE
     * @see SiteTool#getSiteLocales
     */
    @Parameter(defaultValue = "default")
    protected String locale;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    protected String outputTimestamp;

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

    /** The current shared report output directory to use */
    private File reportOutputDirectory;

    /**
     * The report output format: null by default, to represent a site, but can be configured to a Doxia Sink id.
     */
    @Parameter(property = "output.format")
    protected String outputFormat;

    @Component
    private PlexusContainer container;

    /**
     * This method is called when the report generation is invoked directly as a standalone Mojo.
     * This implementation is now marked {@code final} as it is not expected to be overridden:
     * {@code maven-reporting-impl} provides all necessary plumbing.
     *
     * @throws MojoExecutionException if an error occurs when generating the report
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public final void execute() throws MojoExecutionException {
        try {
            if (!canGenerateReport()) {
                String reportMojoInfo = mojoExecution.getPlugin().getId() + ":" + mojoExecution.getGoal();
                getLog().info("Skipping " + reportMojoInfo + " report goal");
                return;
            }
        } catch (MavenReportException e) {
            throw new MojoExecutionException("Failed to determine whether report can be generated", e);
        }

        if (outputFormat != null) {
            reportToMarkup();
        } else {
            reportToSite();
        }
    }

    private void reportToMarkup() throws MojoExecutionException {
        getLog().info("Rendering to " + outputFormat + " markup");

        if (!isExternalReport()) {
            File outputDirectory = new File(getOutputDirectory());
            String filename = getOutputName() + '.' + outputFormat;
            try {
                sinkFactory = container.lookup(SinkFactory.class, outputFormat);
                sink = sinkFactory.createSink(outputDirectory, filename);
            } catch (ComponentLookupException cle) {
                throw new MojoExecutionException(
                        "Cannot find SinkFactory for Doxia output format: " + outputFormat, cle);
            } catch (IOException ioe) {
                throw new MojoExecutionException("Cannot create sink to " + new File(outputDirectory, filename), ioe);
            }
        }

        try {
            Locale locale = getLocale();
            generate(sink, sinkFactory, locale);
        } catch (MavenReportException e) {
            throw new MojoExecutionException(
                    "An error has occurred in " + getName(Locale.ENGLISH) + " report generation.", e);
        } finally {
            if (sink != null) {
                sink.close();
            }
        }
    }

    private void reportToSite() throws MojoExecutionException {
        File outputDirectory = new File(getOutputDirectory());

        String filename = getOutputName() + ".html";

        Locale locale = getLocale();

        try {
            SiteRenderingContext siteContext = createSiteRenderingContext(locale);

            // copy resources
            getSiteRenderer().copyResources(siteContext, outputDirectory);

            String reportMojoInfo = mojoExecution.getPlugin().getId() + ":" + mojoExecution.getGoal();
            DocumentRenderingContext docRenderingContext =
                    new DocumentRenderingContext(outputDirectory, getOutputName(), reportMojoInfo);

            SiteRendererSink sink = new SiteRendererSink(docRenderingContext);

            // TODO Compared to Maven Site Plugin multipage reports will not work and fail with an NPE
            generate(sink, null, locale);

            if (!isExternalReport()) // MSHARED-204: only render Doxia sink if not an external report
            {
                outputDirectory.mkdirs();

                try (Writer writer = new OutputStreamWriter(
                        new FileOutputStream(new File(outputDirectory, filename)), getOutputEncoding())) {
                    // render report
                    getSiteRenderer().mergeDocumentIntoSite(writer, sink, siteContext);
                }
            }

            // copy generated resources also
            getSiteRenderer().copyResources(siteContext, outputDirectory);
        } catch (RendererException | IOException | MavenReportException | SiteToolException e) {
            throw new MojoExecutionException(
                    "An error has occurred in " + getName(Locale.ENGLISH) + " report generation.", e);
        }
    }

    private SiteRenderingContext createSiteRenderingContext(Locale locale)
            throws MavenReportException, IOException, SiteToolException {
        SiteModel siteModel = siteTool.getSiteModel(
                siteDirectory, locale, project, reactorProjects, repoSession, remoteProjectRepositories);

        Map<String, Object> templateProperties = new HashMap<>();
        // We tell the skin that we are rendering in standalone mode
        templateProperties.put("standalone", Boolean.TRUE);
        templateProperties.put("project", getProject());
        templateProperties.put("inputEncoding", getInputEncoding());
        templateProperties.put("outputEncoding", getOutputEncoding());
        // Put any of the properties in directly into the Velocity context
        for (Map.Entry<Object, Object> entry : getProject().getProperties().entrySet()) {
            templateProperties.put((String) entry.getKey(), entry.getValue());
        }

        SiteRenderingContext context;
        try {
            Artifact skinArtifact =
                    siteTool.getSkinArtifactFromRepository(repoSession, remoteProjectRepositories, siteModel.getSkin());

            getLog().info(buffer().a("Rendering content with ")
                    .strong(skinArtifact.getId() + " skin")
                    .toString());

            context = siteRenderer.createContextForSkin(
                    skinArtifact, templateProperties, siteModel, project.getName(), locale);
        } catch (SiteToolException e) {
            throw new MavenReportException("Failed to retrieve skin artifact", e);
        } catch (RendererException e) {
            throw new MavenReportException("Failed to create context for skin", e);
        }

        // Add publish date
        MavenArchiver.parseBuildOutputTimestamp(outputTimestamp).ifPresent(v -> {
            context.setPublishDate(Date.from(v));
        });

        // Generate static site
        context.setRootDirectory(project.getBasedir());

        return context;
    }

    /**
     * Generate a report.
     *
     * @param sink the sink to use for the generation
     * @param locale the wanted locale to generate the report, could be null
     * @throws MavenReportException if any
     * @deprecated use {@link #generate(Sink, SinkFactory, Locale)} instead.
     */
    @Deprecated
    @Override
    public void generate(Sink sink, Locale locale) throws MavenReportException {
        generate(sink, null, locale);
    }

    /**
     * This method is called when the report generation is invoked by maven-site-plugin.
     */
    @Override
    public void generate(Sink sink, SinkFactory sinkFactory, Locale locale) throws MavenReportException {
        this.sink = sink;
        this.sinkFactory = sinkFactory;

        executeReport(locale);
        closeReport();
    }

    /**
     * @return CATEGORY_PROJECT_REPORTS
     */
    @Override
    public String getCategoryName() {
        return CATEGORY_PROJECT_REPORTS;
    }

    @Override
    public File getReportOutputDirectory() {
        if (reportOutputDirectory == null) {
            reportOutputDirectory = new File(getOutputDirectory());
        }

        return reportOutputDirectory;
    }

    @Override
    public void setReportOutputDirectory(File reportOutputDirectory) {
        this.reportOutputDirectory = reportOutputDirectory;
        this.outputDirectory = reportOutputDirectory;
    }

    protected String getOutputDirectory() {
        return outputDirectory.getAbsolutePath();
    }

    protected MavenProject getProject() {
        return project;
    }

    protected Renderer getSiteRenderer() {
        return siteRenderer;
    }

    /**
     * Gets the input files encoding.
     *
     * @return The input files encoding, never <code>null</code>.
     */
    protected String getInputEncoding() {
        return (inputEncoding == null) ? ReaderFactory.FILE_ENCODING : inputEncoding;
    }

    /**
     * Gets the effective reporting output files encoding.
     *
     * @return The effective reporting output file encoding, never <code>null</code>.
     */
    protected String getOutputEncoding() {
        return (outputEncoding == null) ? WriterFactory.UTF_8 : outputEncoding;
    }

    /**
     * Gets the locale.
     *
     * @return the locale for this standalone report
     */
    protected Locale getLocale() {
        return siteTool.getSiteLocales(locale).get(0);
    }

    /**
     * Actions when closing the report.
     */
    protected void closeReport() {
        if (getSink() != null) {
            getSink().close();
        }
    }

    /**
     * @return the sink used
     */
    public Sink getSink() {
        return sink;
    }

    /**
     * @return the sink factory used
     */
    public SinkFactory getSinkFactory() {
        return sinkFactory;
    }

    /**
     * @see org.apache.maven.reporting.MavenReport#isExternalReport()
     * @return {@code false} by default.
     */
    @Override
    public boolean isExternalReport() {
        return false;
    }

    @Override
    public boolean canGenerateReport() throws MavenReportException {
        return true;
    }

    /**
     * Execute the generation of the report.
     *
     * @param locale the wanted locale to return the report's description, could be <code>null</code>.
     * @throws MavenReportException if any
     */
    protected abstract void executeReport(Locale locale) throws MavenReportException;

    /**
     * Returns the (Test) Source XRef location as passthrough if provided, otherwise returns the
     * default value.
     *
     * @param location the XRef location provided via plugin parameter, if any
     * @param test whether it is test source
     * @return the actual (Test) Source XRef location
     */
    protected File getXrefLocation(File location, boolean test) {
        return location != null ? location : new File(getReportOutputDirectory(), test ? "xref-test" : "xref");
    }

    /**
     * Contructs the (Test) Source XRef location relative to the {@link #getReportOutputDirectory()}
     * with {@link #getXrefLocation(File, boolean)}.
     *
     * @param location the XRef location provided via plugin parameter, if any
     * @param test whether it is test source
     * @return the constructed (Test) Source XRef location
     */
    protected String constructXrefLocation(File location, boolean test) {
        String constructedLocation = null;
        File xrefLocation = getXrefLocation(location, test);

        String relativePath =
                PathTool.getRelativePath(getReportOutputDirectory().getAbsolutePath(), xrefLocation.getAbsolutePath());
        if (relativePath == null || relativePath.isEmpty()) {
            relativePath = ".";
        }
        relativePath = relativePath + "/" + xrefLocation.getName();
        if (xrefLocation.exists()) {
            // XRef was already generated by manual execution of a lifecycle binding
            constructedLocation = relativePath;
        } else {
            // Not yet generated - check if the report is on its way
            Reporting reporting = project.getModel().getReporting();
            List<ReportPlugin> reportPlugins =
                    reporting != null ? reporting.getPlugins() : Collections.<ReportPlugin>emptyList();
            for (ReportPlugin plugin : reportPlugins) {
                String artifactId = plugin.getArtifactId();
                if ("maven-jxr-plugin".equals(artifactId)) {
                    constructedLocation = relativePath;
                }
            }
        }

        if (constructedLocation == null) {
            getLog().warn("Unable to locate" + (test ? " Test" : "") + " Source XRef to link to -- DISABLED");
        }
        return constructedLocation;
    }
}
