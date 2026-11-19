<!---
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# Apache Maven Reporting Implementation

[![Apache License, Version 2.0, January 2004](https://img.shields.io/github/license/apache/maven.svg?label=License)](https://www.apache.org/licenses/LICENSE-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/org.apache.maven.reporting/maven-reporting-impl.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/org.apache.maven.reporting/maven-reporting-impl)
[![Reproducible Builds](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/jvm-repo-rebuild/reproducible-central/master/content/org/apache/maven/reporting/maven-reporting-impl/badge.json)](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/org/apache/maven/reporting/maven-reporting-impl/README.md)
[![Verify](https://github.com/apache/maven-reporting-impl/actions/workflows/maven-verify.yml/badge.svg)](https://github.com/apache/maven-reporting-impl/actions/workflows/maven-verify.yml)

The base class for Maven report plugins. A report extends `AbstractMavenReport`, implements
`executeReport(Locale)`, and then runs both inside `mvn site`, through the
[Maven Reporting API](https://maven.apache.org/shared/maven-reporting-api/), and as a standalone goal that renders
its own page with the site skin, or writes it in a Doxia markup format with `-Doutput.format`.

- Documentation: <https://maven.apache.org/shared/maven-reporting-impl/>
- Writing a report plugin: <https://maven.apache.org/guides/plugin/guide-java-report-plugin-development.html>
- Issues: <https://github.com/apache/maven-reporting-impl/issues>

## Build

```shell
mvn verify
```

The integration tests under `src/it` build a sample report plugin and run it both ways.
They are bound to the `verify` phase, so the `mvn verify` command above runs all of them.
To run just one, select it by its directory name:

```shell
mvn verify -Dinvoker.test=use-as-direct-mojo
```

## Contributing

Send pull requests against `master`; pull request titles become entries in the release notes.
For code style, mailing lists and the rest of the process, see the
[shared Maven contributor guide](https://maven.apache.org/developers/).
