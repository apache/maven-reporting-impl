package org.apache.maven.reporting.its.custom;

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

import java.util.Locale;

import java.io.IOException;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.reporting.MavenReportRenderer;

/**
 * Sample multi-page report.
 */
@Mojo( name = "multi-page" )
public class MultiPageReport
    extends CustomReportWithRenderer
{
    /**
     * @deprecated use {@link #getOutputPath()} instead
     */
    @Deprecated
    public String getOutputName()
    {
        return getOutputPath();
    }

    public String getOutputPath()
    {
        return "multi-page";
    }

    public String getName( Locale locale )
    {
        return "Multi Page Maven Report";
    }

    public String getDescription( Locale locale )
    {
        return "Multi Page Maven Report Description";
    }

    protected void executeReport( Locale locale )
        throws MavenReportException
    {
        super.executeReport(locale);

        try {
            Sink second = getSinkFactory().createSink(outputDirectory, "multi-second.html");
            // render content that differs from the main page, so that a test can tell the two pages apart
            MavenReportRenderer r = new CustomReportRenderer(second)
            {
                public String getTitle()
                {
                    return "Second Page Title";
                }

                protected void renderBody()
                {
                    startSection( "second section" );
                    text( "Second page content." );
                    endSection();
                }
            };
            r.render();
        } catch (IOException e) {
            throw new MavenReportException("Could not create sink", e);
        }
    }
}
