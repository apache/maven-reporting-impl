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

import java.util.List;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Maven 4 API gap: Doxia's {@code SiteTool} and {@code Renderer} still take {@link MavenProject},
 * {@link RepositorySystemSession} and Maven 3 artifacts, and are Sisu components of the plugin realm. The Maven 4 API
 * has no public way to get any of these from a {@link Project} or {@link Session}. The implementation classes that
 * hold them ({@code org.apache.maven.internal.impl.DefaultSession#getMavenSession()} and
 * {@code DefaultProject#getProject()}) are not visible from the plugin realm, so they are reached by reflection.
 * This is the only place that does so; it goes away once the Doxia site tools are ported to the Maven 4 API.
 * <p>
 * Known limitation (Maven 4.0.0-rc-7): this does not work at runtime. A Maven 4 plugin realm does not import
 * {@code org.apache.maven.execution.MavenSession} and the other maven-core classes, so the cast fails with a
 * {@link NoClassDefFoundError} and the standalone site and markup rendering of {@code AbstractMavenReport} is broken.
 */
class LegacyMavenBridge {
    private final MavenSession mavenSession;

    private final MavenProject mavenProject;

    LegacyMavenBridge(Session session, Project project) {
        this.mavenSession = (MavenSession) invoke(session, "getMavenSession");
        this.mavenProject = (MavenProject) invoke(project, "getProject");
    }

    private static Object invoke(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Cannot get the Maven 3 object from " + target.getClass().getName() + "#" + method + "()", e);
        }
    }

    PlexusContainer getPlexusContainer() {
        return mavenSession.getContainer();
    }

    MavenProject getMavenProject() {
        return mavenProject;
    }

    List<MavenProject> getReactorProjects() {
        return mavenSession.getProjects();
    }

    RepositorySystemSession getRepositorySystemSession() {
        return mavenSession.getRepositorySession();
    }

    List<RemoteRepository> getRemoteProjectRepositories() {
        return mavenProject.getRemoteProjectRepositories();
    }
}
