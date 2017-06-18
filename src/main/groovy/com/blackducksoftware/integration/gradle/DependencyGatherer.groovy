package com.blackducksoftware.integration.gradle

import java.lang.reflect.Method

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.artifacts.ResolvedDependency
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import com.blackducksoftware.integration.hub.bdio.simple.model.DependencyNode
import com.blackducksoftware.integration.hub.bdio.simple.model.externalid.MavenExternalId
import com.blackducksoftware.integration.util.ExcludedIncludedFilter

class DependencyGatherer {
    private final Logger logger = LoggerFactory.getLogger(DependencyGatherer.class)

    private Set<String> alreadyAddedIds

    DependencyNode getFullyPopulatedRootNode(final Project rootProject, String excludedProjectNames, String includedProjectNames, String excludedConfigurationNames, String includedConfigurationNames) {
        /**
         * getName() returns a String, while getGroup() and getVersion() both return Object. Gradle's javadoc indicates that using the toString() is appropriate
         * https://docs.gradle.org/3.5/javadoc/org/gradle/api/Project.html#getGroup()
         * https://docs.gradle.org/3.5/javadoc/org/gradle/api/Project.html#getVersion()
         */
        def group = rootProject.group.toString()
        def name = rootProject.name.toString()
        def version = rootProject.version.toString()
        DependencyNode rootProjectNode = new DependencyNode(name, version, new MavenExternalId(group, name, version))

        ExcludedIncludedFilter projectFilter = new ExcludedIncludedFilter(excludedProjectNames, includedProjectNames)
        ExcludedIncludedFilter configurationFilter = new ExcludedIncludedFilter(excludedConfigurationNames, includedConfigurationNames)
        alreadyAddedIds = new HashSet<>()

        rootProject.allprojects.each { project ->
            if (projectFilter.shouldInclude(project.name)) {
                project.configurations.each { configuration ->
                    ResolvedConfiguration resolvedConfiguration = resolveConfiguration(configuration, configurationFilter)
                    if (resolvedConfiguration != null) {
                        resolvedConfiguration.firstLevelModuleDependencies.each { dependency ->
                            addDependencyNodeToParent(rootProjectNode, dependency)
                        }
                    }
                }
            }
        }

        return rootProjectNode;
    }

    private ResolvedConfiguration resolveConfiguration(Configuration configuration, ExcludedIncludedFilter configurationFilter) {
        if (!configurationFilter.shouldInclude(configuration.name)) {
            return null
        }
        try {
            Method isCanBeResolved = Configuration.class.getMethod("isCanBeResolved")
            boolean result = isCanBeResolved.invoke(configuration)
            if (result) {
                return configuration.resolvedConfiguration
            } else {
                return null
            }
        } catch (Exception e) {
            //Exceptions are likely here since we are trying to invoke a method that may not exist (isCanBeResolved was added in 3.3)
            logger.debug("Trying to invoke isCanBeResolved threw an Exception (likely not an issue): ${e.message}")
        }

        try {
            return configuration.resolvedConfiguration
        } catch (Exception e) {
            //Exceptions are unlikely here since there should not be any configurations that can not be resolved without the isCanBeResolved method
            logger.error("Tried to resolve a configuration that can't be resolved and the isCanBeResolved method doesn't appear to exist: ${e.message}")
        }
        return null
    }

    private void addDependencyNodeToParent(DependencyNode parentDependencyNode, final ResolvedDependency resolvedDependency) {
        def group = resolvedDependency.moduleGroup
        def name = resolvedDependency.moduleName
        def version = resolvedDependency.moduleVersion

        def mavenExternalId = new MavenExternalId(group, name, version)
        def dependencyNode = new DependencyNode(name, version, mavenExternalId)
        parentDependencyNode.children.add(dependencyNode)
        if (alreadyAddedIds.add(mavenExternalId.createDataId())) {
            for (ResolvedDependency child : resolvedDependency.getChildren()) {
                addDependencyNodeToParent(dependencyNode, child)
            }
        }
    }
}
