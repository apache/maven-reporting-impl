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
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.ReportPlugin;
import org.apache.maven.api.model.Reporting;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.Lookup;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.sink.SinkFactory;
import org.apache.maven.doxia.site.SiteModel;
import org.apache.maven.doxia.siterenderer.DocumentRenderingContext;
import org.apache.maven.doxia.siterenderer.RendererException;
import org.apache.maven.doxia.siterenderer.SiteRenderer;
import org.apache.maven.doxia.siterenderer.SiteRenderingContext;
import org.apache.maven.doxia.siterenderer.sink.SiteRendererSink;
import org.apache.maven.doxia.tools.SiteTool;
import org.apache.maven.doxia.tools.SiteToolException;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

/**
 * The basis for a Maven report which can be generated both as part of a site generation or
 * as a direct standalone goal invocation.
 * Both invocations are delegated to <code>abstract executeReport( Locale )</code> from:
 * <ul>
 * <li>Mojo's <code>execute()</code> method, see maven-api-core</li>
 * <li>MavenMultiPageReport's <code>generate( Sink, SinkFactory, Locale )</code>, see maven-reporting-api</li>
 * </ul>
 *
 * @author <a href="evenisse@apache.org">Emmanuel Venisse</a>
 * @since 2.0
 * @see #execute() <code>Mojo.execute()</code>, from maven-api-core
 * @see #generate(Sink, SinkFactory, Locale) <code>MavenMultiPageReport.generate( Sink, SinkFactory, Locale )</code>,
 *  from maven-reporting-api
 * @see #executeReport(Locale) <code>abstract executeReport( Locale )</code>
 */
public abstract class AbstractMavenReport implements Mojo, MavenMultiPageReport {
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
    @Inject
    protected Project project;

    /**
     * The Maven Session.
     */
    @Inject
    protected Session session;

    /**
     * The mojo execution.
     */
    @Inject
    protected MojoExecution mojoExecution;

    /**
     * The mojo logger.
     */
    @Inject
    protected Log log;

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
     * Directory containing the <code>site.xml</code> file.
     */
    @Parameter(defaultValue = "${basedir}/src/site")
    protected File siteDirectory;

    /**
     * The locale to use when the report generation is invoked directly as a standalone Mojo.
     *
     * @see SiteTool#DEFAULT_LOCALE
     * @see SiteTool#getSiteLocales(String)
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

    /** Doxia SiteTool, looked up lazily: the Maven 4 DI does not see Sisu/Plexus components. */
    private SiteTool siteTool;

    /** Doxia site renderer component, looked up lazily for the same reason. */
    private SiteRenderer siteRenderer;

    /**
     * This method is called when the report generation is invoked directly as a standalone Mojo.
     * This implementation is now marked {@code final} as it is not expected to be overridden:
     * {@code maven-reporting-impl} provides all necessary plumbing.
     *
     * @throws MojoException if an error occurs when generating the report
     * @see org.apache.maven.api.plugin.Mojo#execute()
     */
    @Override
    public final void execute() throws MojoException {
        try {
            if (!canGenerateReport()) {
                String reportMojoInfo = mojoExecution.getPlugin().getModel().getId() + ":" + mojoExecution.getGoal();
                getLog().info("Skipping " + reportMojoInfo + " report goal");
                return;
            }
        } catch (MavenReportException e) {
            throw new MojoException("Failed to determine whether report can be generated", e);
        }

        if (outputFormat != null) {
            reportToMarkup();
        } else {
            reportToSite();
        }
    }

    private void reportToMarkup() throws MojoException {
        Path relativeOutput = getProject().getBasedir().relativize(new File(getOutputDirectory()).toPath());
        if (isExternalReport()) {
            getLog().info("Rendering external report to " + relativeOutput.resolve(getOutputPath()));
        } else {
            String filename = getOutputPath() + '.' + outputFormat;
            getLog().info("Rendering report as " + outputFormat + " markup to " + relativeOutput.resolve(filename));

            try {
                sinkFactory = lookupInPluginRealm(SinkFactory.class, outputFormat);
                sink = sinkFactory.createSink(new File(getOutputDirectory()), filename);
            } catch (org.apache.maven.api.services.LookupException le) {
                throw new MojoException("Cannot find SinkFactory for Doxia output format: " + outputFormat, le);
            } catch (IOException ioe) {
                throw new MojoException("Cannot create sink to " + new File(outputDirectory, filename), ioe);
            }
        }

        try {
            Locale locale = getLocale();
            generate(
                    sink,
                    new AbstractSinkFactoryAdapter(sinkFactory) {
                        @Override
                        public Sink createSink(File file, String filename) throws IOException {
                            getLog().info("          " + relativeOutput.resolve(filename));
                            return super.createSink(file, filename);
                        }
                    },
                    locale);
        } catch (MavenReportException e) {
            throw new MojoException("An error has occurred in " + getName(Locale.ENGLISH) + " report generation.", e);
        } finally {
            if (sink != null) {
                sink.close();
            }
        }
    }

    private void reportToSite() throws MojoException {
        String filename = getOutputPath() + ".html";

        Path relativeOutput = getProject().getBasedir().relativize(new File(getOutputDirectory()).toPath());
        if (isExternalReport()) {
            getLog().info("Rendering external report to " + relativeOutput.resolve(getOutputPath()));
        } else {
            getLog().info("Rendering report to " + relativeOutput.resolve(filename));
        }

        File outputDirectory = new File(getOutputDirectory());

        Locale locale = getLocale();

        try {
            SiteRenderingContext siteContext = createSiteRenderingContext(locale);

            // copy resources
            getSiteRenderer().copyResources(siteContext, outputDirectory);

            String reportMojoInfo = mojoExecution.getPlugin().getModel().getId() + ":" + mojoExecution.getGoal();
            DocumentRenderingContext docRenderingContext =
                    new DocumentRenderingContext(outputDirectory, getOutputPath(), reportMojoInfo);

            SiteRendererSink sink = new SiteRendererSink(docRenderingContext);

            // TODO Compared to Maven Site Plugin multipage reports will not work and fail with an NPE
            generate(sink, null, locale);

            if (!isExternalReport()) { // MSHARED-204: only render Doxia sink if not an external report
                Files.createDirectories(outputDirectory.toPath());

                try (Writer writer = new OutputStreamWriter(
                        Files.newOutputStream(outputDirectory.toPath().resolve(filename)),
                        Charset.forName(getOutputEncoding()))) {
                    // render report
                    getSiteRenderer().mergeDocumentIntoSite(writer, sink, siteContext);
                }
            }

            // copy generated resources also
            getSiteRenderer().copyResources(siteContext, outputDirectory);
        } catch (RendererException | IOException | MavenReportException | SiteToolException e) {
            throw new MojoException("An error has occurred in " + getName(Locale.ENGLISH) + " report generation.", e);
        }
    }

    private SiteRenderingContext createSiteRenderingContext(Locale locale)
            throws MavenReportException, IOException, SiteToolException {
        // Doxia's SiteTool and SiteRenderer still speak the Maven 3 API (MavenProject, RepositorySystemSession, ...)
        LegacyMavenBridge bridge = new LegacyMavenBridge(session, project);

        SiteModel siteModel = getSiteTool()
                .getSiteModel(
                        siteDirectory,
                        locale,
                        bridge.getMavenProject(),
                        bridge.getReactorProjects(),
                        bridge.getRepositorySystemSession(),
                        bridge.getRemoteProjectRepositories());

        Map<String, Object> templateProperties = new HashMap<>();
        // We tell the skin that we are rendering in standalone mode
        templateProperties.put("standalone", Boolean.TRUE);
        templateProperties.put("project", bridge.getMavenProject());
        templateProperties.put("inputEncoding", getInputEncoding());
        templateProperties.put("outputEncoding", getOutputEncoding());
        // Put any of the properties in directly into the Velocity context
        for (Map.Entry<String, String> entry :
                getProject().getModel().getProperties().entrySet()) {
            templateProperties.put(entry.getKey(), entry.getValue());
        }

        SiteRenderingContext context;
        try {
            Artifact skinArtifact = getSiteTool()
                    .getSkinArtifactFromRepository(
                            bridge.getRepositorySystemSession(),
                            bridge.getRemoteProjectRepositories(),
                            siteModel.getSkin());

            if (!isExternalReport()) {
                getLog().info(session.getService(MessageBuilderFactory.class)
                        .builder()
                        .a("          using ")
                        .strong(skinArtifact.getId() + " site skin")
                        .build());
            }

            context = getSiteRenderer()
                    .createContextForSkin(
                            skinArtifact,
                            templateProperties,
                            siteModel,
                            project.getModel().getName(),
                            locale);
        } catch (SiteToolException e) {
            throw new MavenReportException("Failed to retrieve skin artifact", e);
        } catch (RendererException e) {
            throw new MavenReportException("Failed to create context for skin", e);
        }

        // Add publish date
        parseBuildOutputTimestamp(outputTimestamp).ifPresent(v -> {
            context.setPublishDate(Date.from(v));
        });

        // Generate static site
        context.setRootDirectory(project.getBasedir().toFile());

        return context;
    }

    /**
     * Parses {@code project.build.outputTimestamp} the way maven-archiver does: ISO 8601, seconds since the epoch,
     * or the {@code SOURCE_DATE_EPOCH} environment variable as fallback.
     */
    private static Optional<Instant> parseBuildOutputTimestamp(String outputTimestamp) {
        if (outputTimestamp == null || (outputTimestamp.length() < 2 && !isNumeric(outputTimestamp))) {
            outputTimestamp = System.getenv("SOURCE_DATE_EPOCH");
            if (outputTimestamp == null) {
                return Optional.empty();
            }
        }
        if (isNumeric(outputTimestamp)) {
            return Optional.of(Instant.ofEpochSecond(Long.parseLong(outputTimestamp)));
        }
        try {
            return Optional.of(OffsetDateTime.parse(outputTimestamp)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.SECONDS)
                    .toInstant());
        } catch (DateTimeParseException pe) {
            throw new IllegalArgumentException(
                    "Invalid project.build.outputTimestamp value '" + outputTimestamp + "'", pe);
        }
    }

    private static boolean isNumeric(String str) {
        return !str.isEmpty() && str.chars().allMatch(c -> c >= '0' && c <= '9');
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

    protected Project getProject() {
        return project;
    }

    /**
     * Gets the mojo logger.
     *
     * @return the logger, never <code>null</code>
     */
    protected Log getLog() {
        return log;
    }

    /**
     * Gets the projects of the current reactor.
     *
     * @return the reactor projects
     */
    protected List<Project> getReactorProjects() {
        return session.getProjects();
    }

    protected SiteRenderer getSiteRenderer() {
        if (siteRenderer == null) {
            siteRenderer = lookupInPluginRealm(SiteRenderer.class, null);
        }
        return siteRenderer;
    }

    /**
     * Looks up a Doxia component (a Sisu/Plexus component living in the plugin realm) which the Maven 4 DI and
     * {@link Lookup} cannot see from a Maven 4 mojo unless the lookup realm is switched to the plugin realm.
     */
    private <T> T lookupInPluginRealm(Class<T> type, String hint) {
        PlexusContainer container = new LegacyMavenBridge(session, project).getPlexusContainer();
        ClassRealm previous = container.getLookupRealm();
        try {
            container.setLookupRealm((ClassRealm) mojoExecution.getPlugin().getClassLoader());
            return hint == null ? container.lookup(type) : container.lookup(type, hint);
        } catch (ComponentLookupException e) {
            throw new org.apache.maven.api.services.LookupException(e);
        } finally {
            container.setLookupRealm(previous);
        }
    }

    /**
     * Gets the Doxia site tool.
     *
     * @return the site tool
     */
    protected SiteTool getSiteTool() {
        if (siteTool == null) {
            siteTool = lookupInPluginRealm(SiteTool.class, null);
        }
        return siteTool;
    }

    /**
     * Gets the input files encoding.
     *
     * @return The input files encoding, never <code>null</code>.
     */
    protected String getInputEncoding() {
        return (inputEncoding == null) ? Charset.defaultCharset().name() : inputEncoding;
    }

    /**
     * Gets the effective reporting output files encoding.
     *
     * @return The effective reporting output file encoding, never <code>null</code>.
     */
    protected String getOutputEncoding() {
        return (outputEncoding == null) ? StandardCharsets.UTF_8.name() : outputEncoding;
    }

    /**
     * Gets the locale.
     *
     * @return the locale for this standalone report
     */
    protected Locale getLocale() {
        return getSiteTool().getSiteLocales(locale).get(0);
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

        String relativePath = String.valueOf(getReportOutputDirectory().toPath().relativize(xrefLocation.toPath()));
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
