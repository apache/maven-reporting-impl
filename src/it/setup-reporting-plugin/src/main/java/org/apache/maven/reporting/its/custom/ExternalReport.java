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

import java.io.IOException;
import java.io.File;
import java.util.Locale;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.shared.utils.io.FileUtils;

/**
 * Typical code to copy as an external reporting plugin start: choose the goal name, then implement getOutputName(),
 * getName( Locale ), getDescription( Locale ) and of course executeReport( Locale ).
 */
@Mojo( name = "external" )
public class ExternalReport
    extends AbstractMavenReport
{
    /**
     * The name of the output directory inside the reports.
     */
    @Parameter( property = "outputDirName", defaultValue = "external" )
    private String outputDirName;

    public String getOutputName()
    {
        return outputDirName + "/report";
    }

    public String getName( Locale locale )
    {
        return "External Maven Report";
    }

    public String getDescription( Locale locale )
    {
        return "External Maven Report Description";
    }

    public boolean isExternalReport()
    {
        return true;
    }

    @Override
    protected void executeReport( Locale locale )
        throws MavenReportException
    {
        try
        {
            executeExternalTool( new File( getReportOutputDirectory(), outputDirName ) );
        }
        catch ( IOException ioe )
        {
            throw new MavenReportException( "IO exception while executing external reporting tool", ioe );
        }
    }

    /**
     * Invoke the external tool to generate report.
     *
     * @param destDir destination directory
     */
    private void executeExternalTool( File destDir )
        throws IOException
    {
        getLog().info( "Running external tool to " + destDir );

        // demo implementation, to be replaced with effective tool
        destDir.mkdirs();

        File reportFile = new File( destDir, "report.html" );
        FileUtils.fileWrite( reportFile, "UTF-8", "<html><body><h1>External Report</h1></body></html>" );
    }
}
