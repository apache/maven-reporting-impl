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

import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.sink.SinkFactory;

import java.util.Locale;

/**
 * Temporary copy of <a href="http://maven.apache.org/shared/maven-reporting-api/"><code>maven-reporting-api</code></a>
 * class to avoid Maven 3 prerequisite, since <code>maven-reporting-api</code> is included
 * in Maven 2 then internal version is preferred.
 */
public interface MavenMultiPageReport
    extends MavenReport
{
    void generate( Sink sink, SinkFactory sinkFactory, Locale locale )
        throws MavenReportException;
}
