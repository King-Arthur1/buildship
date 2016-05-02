/*
 * Copyright (c) 2015 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Etienne Studer & Donát Csikós (Gradle Inc.) - initial API and implementation and initial documentation
 *     Simon Scholz <simon.scholz@vogella.com> - Bug 473348
 */

package org.eclipse.buildship.core.workspace.internal;

import java.io.File;
import java.util.List;
import java.util.Set;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import com.gradleware.tooling.toolingmodel.OmniEclipseGradleBuild;
import com.gradleware.tooling.toolingmodel.OmniEclipseLinkedResource;
import com.gradleware.tooling.toolingmodel.OmniEclipseProject;
import com.gradleware.tooling.toolingmodel.OmniGradleProject;
import com.gradleware.tooling.toolingmodel.repository.FixedRequestAttributes;
import com.gradleware.tooling.toolingmodel.util.Maybe;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;

import org.eclipse.buildship.core.CorePlugin;
import org.eclipse.buildship.core.configuration.GradleProjectNature;
import org.eclipse.buildship.core.configuration.ProjectConfiguration;
import org.eclipse.buildship.core.util.file.RelativePathUtils;
import org.eclipse.buildship.core.workspace.GradleClasspathContainer;
import org.eclipse.buildship.core.workspace.NewProjectHandler;

/**
 * Synchronizes the given Gradle build with the Eclipse workspace. The algorithm is as follows:
 * <p/>
 * <ol>
 * <li>Uncouple all open workspace projects for which there is no corresponding Gradle project in the Gradle build anymore
 * <ol>
 * <li>the Gradle nature is removed</li>
 * <li>the derived resource markers are removed</li>
 * <li>the Gradle settings file is removed</li>
 * </ol>
 * </li>
 * <li>Synchronize all Gradle projects of the Gradle build with the Eclipse workspace project counterparts:
 * <ul>
 * <li>
 * If there is a project in the workspace at the location of the Gradle project, the synchronization is as follows:
 * <ol>
 * <li>If the workspace project is closed, the project is left unchanged</li>
 * <li>If the workspace project is open:
 * <ul>
 * <li>the project name is updated</li>
 * <li>the Gradle nature is set</li>
 * <li>the Gradle settings file is written</li>
 * <li>the linked resources are set</li>
 * <li>the derived resources are marked</li>
 * <li>the project natures and build commands are set</li>
 * <li>if the Gradle project is a Java project
 * <ul>
 * <li>the Java nature is added </li>
 * <li>the source compatibility settings are updated</li>
 * <li>the set of source folders is updated</li>
 * <li>the Gradle classpath container is updated</li>
 * </ul>
 * </li>
 * </ul>
 * </li>
 * </ol>
 * </li>
 * <li>
 * If there is an Eclipse project at the location of the Gradle project, i.e. there is a .project file in that folder, then
 * the {@link NewProjectHandler} decides whether to import it and whether to keep or overwrite that existing .project file.
 * The imported project is then synchronized as specified above.
 * </li>
 * <li>If there is no project in the workspace, nor an Eclipse project at the location of the Gradle build, then
 * the {@link NewProjectHandler} decides whether to import it.
 * The imported project is then synchronized as specified above.
 * </li>
 * </ul>
 * </li>
 * </ol>
 *
 * <p/>
 * This operation changes resources. It will acquire the workspace scheduling rule to ensure an atomic operation.
 *
 */
final class SynchronizeGradleBuildOperation implements IWorkspaceRunnable {

    private final OmniEclipseGradleBuild gradleBuild;
    private final FixedRequestAttributes requestAttributes;
    private final NewProjectHandler newProjectHandler;

    SynchronizeGradleBuildOperation(OmniEclipseGradleBuild gradleBuild, FixedRequestAttributes requestAttributes, NewProjectHandler newProjectHandler) {
        this.gradleBuild = gradleBuild;
        this.requestAttributes = requestAttributes;
        this.newProjectHandler = newProjectHandler;
    }

    @Override
    public void run(IProgressMonitor monitor) throws CoreException {
        JavaCore.run(new IWorkspaceRunnable() {
            @Override
            public void run(IProgressMonitor monitor) throws CoreException {
                synchronizeGradleBuildWithWorkspace(monitor);
            }
        }, monitor);
    };

    private void synchronizeGradleBuildWithWorkspace(IProgressMonitor monitor) throws CoreException {
        // collect Gradle projects and Eclipse workspace projects to sync
        List<OmniEclipseProject> allGradleProjects = getAllGradleProjects();
        List<IProject> decoupledWorkspaceProjects = getOpenWorkspaceProjectsRemovedFromGradleBuild();
        SubMonitor progress = SubMonitor.convert(monitor, decoupledWorkspaceProjects.size() + allGradleProjects.size());

        // uncouple the open workspace projects that do not have a corresponding Gradle project anymore
        for (IProject project : decoupledWorkspaceProjects) {
            uncoupleWorkspaceProjectFromGradle(project, progress.newChild(1));
        }

        // synchronize the Gradle projects with their corresponding workspace projects
        for (OmniEclipseProject gradleProject : allGradleProjects) {
            synchronizeGradleProjectWithWorkspaceProject(gradleProject, progress.newChild(1));
        }
    }

    private List<IProject> getOpenWorkspaceProjectsRemovedFromGradleBuild() {
        // in the workspace, find all projects with a Gradle nature that belong to the same Gradle build (based on the root project directory) but
        // which do not match the location of one of the Gradle projects of that build
        final Set<File> gradleProjectDirectories = FluentIterable.from(getAllGradleProjects()).transform(new Function<OmniEclipseProject, File>() {

            @Override
            public File apply(OmniEclipseProject gradleProject) {
                return gradleProject.getProjectDirectory();
            }
        }).toSet();

        ImmutableList<IProject> allWorkspaceProjects = CorePlugin.workspaceOperations().getAllProjects();
        return FluentIterable.from(allWorkspaceProjects).filter(GradleProjectNature.isPresentOn()).filter(new Predicate<IProject>() {

            @Override
            public boolean apply(IProject project) {
                ProjectConfiguration projectConfiguration = CorePlugin.projectConfigurationManager().readProjectConfiguration(project);
                return projectConfiguration.getRequestAttributes().getProjectDir().equals(SynchronizeGradleBuildOperation.this.requestAttributes.getProjectDir()) &&
                        (project.getLocation() == null || !gradleProjectDirectories.contains(project.getLocation().toFile()));
            }
        }).toList();
    }

    private void synchronizeGradleProjectWithWorkspaceProject(OmniEclipseProject project, SubMonitor progress) throws CoreException {
        progress.setWorkRemaining(1);
        progress.subTask(String.format("Synchronize Gradle project %s with workspace project", project.getName()));
        // check if a project already exists in the workspace at the location of the Gradle project to import
        Optional<IProject> workspaceProject = CorePlugin.workspaceOperations().findProjectByLocation(project.getProjectDirectory());
        SubMonitor childProgress = progress.newChild(1, SubMonitor.SUPPRESS_ALL_LABELS);
        if (workspaceProject.isPresent()) {
            synchronizeWorkspaceProject(project, workspaceProject.get(), childProgress);
        } else {
            if (project.getProjectDirectory().exists() && this.newProjectHandler.shouldImport(project)) {
                synchronizeNonWorkspaceProject(project, childProgress);
            }
        }
    }

    private void synchronizeWorkspaceProject(OmniEclipseProject project, IProject workspaceProject, SubMonitor progress) throws CoreException {
        if (workspaceProject.isAccessible()) {
            synchronizeOpenWorkspaceProject(project, workspaceProject, progress);
        } else {
            synchronizeClosedWorkspaceProject(progress);
        }
    }

    private void synchronizeOpenWorkspaceProject(OmniEclipseProject project, IProject workspaceProject, SubMonitor progress) throws CoreException {
        progress.setWorkRemaining(12);
        // sync the Eclipse project with the file system first
        CorePlugin.workspaceOperations().refreshProject(workspaceProject, progress.newChild(1));

        // update the project name in case the Gradle project name has changed
        workspaceProject = ProjectNameUpdater.updateProjectName(workspaceProject, project, this.gradleBuild, progress.newChild(1));

        // add Gradle nature, if needed
        CorePlugin.workspaceOperations().addNature(workspaceProject, GradleProjectNature.ID, progress.newChild(1));

        // persist the Gradle-specific configuration in the Eclipse project's .settings folder, if the configuration is available
        if (this.requestAttributes != null) {
            ProjectConfiguration configuration = ProjectConfiguration.from(this.requestAttributes, project);
            CorePlugin.projectConfigurationManager().saveProjectConfiguration(configuration, workspaceProject);
        }

        // update linked resources
        LinkedResourcesUpdater.update(workspaceProject, project.getLinkedResources(), progress.newChild(1));

        // mark derived folders
        markGradleSpecificFolders(project, workspaceProject, progress.newChild(1));

        SubMonitor javaProgress = progress.newChild(5);
        if (isJavaProject(project)) {
            IJavaProject javaProject;
            if (hasJavaNature(workspaceProject)) {
                javaProgress.newChild(1);
                javaProject = JavaCore.create(workspaceProject);
            } else {
                IPath jrePath = JavaRuntime.getDefaultJREContainerEntry().getPath();
                IClasspathEntry classpathContainer = GradleClasspathContainer.newClasspathEntry();
                javaProject = CorePlugin.workspaceOperations().createJavaProject(workspaceProject, jrePath, classpathContainer, javaProgress.newChild(1));
            }
            JavaSourceSettingsUpdater.update(javaProject, project.getJavaSourceSettings().get(), javaProgress.newChild(1));
            SourceFolderUpdater.update(javaProject, project.getSourceDirectories(), javaProgress.newChild(1));
            ClasspathContainerUpdater.updateFromModel(javaProject, project, javaProgress.newChild(1));
            WtpClasspathUpdater.update(javaProject, project, javaProgress.newChild(1));
        }

        // set project natures and build commands
        ProjectNatureUpdater.update(workspaceProject, project.getProjectNatures(), progress.newChild(1));
        BuildCommandUpdater.update(workspaceProject, project.getBuildCommands(), progress.newChild(1));
    }

    private void synchronizeClosedWorkspaceProject(SubMonitor childProgress) {
        // do not modify closed projects
    }

    private void synchronizeNonWorkspaceProject(OmniEclipseProject project, SubMonitor progress) throws CoreException {
        progress.setWorkRemaining(2);
        IProject workspaceProject;

        // check if an Eclipse project already exists at the location of the Gradle project to import
        Optional<IProjectDescription> projectDescription = CorePlugin.workspaceOperations().findProjectDescriptor(project.getProjectDirectory(), progress.newChild(1));
        if (projectDescription.isPresent()) {
            if (this.newProjectHandler.shouldOverwriteDescriptor(projectDescription.get(), project)) {
                CorePlugin.workspaceOperations().deleteProjectDescriptors(project.getProjectDirectory());
                workspaceProject = addNewEclipseProjectToWorkspace(project, progress.newChild(1));
            } else {
                workspaceProject = addExistingEclipseProjectToWorkspace(project, projectDescription.get(), progress.newChild(1));
            }
        } else {
            workspaceProject = addNewEclipseProjectToWorkspace(project, progress.newChild(1));
        }

        this.newProjectHandler.afterImport(workspaceProject, project);
    }

    private IProject addExistingEclipseProjectToWorkspace(OmniEclipseProject project, IProjectDescription projectDescription, SubMonitor progress) throws CoreException {
        progress.setWorkRemaining(3);
        ProjectNameUpdater.ensureProjectNameIsFree(project, this.gradleBuild, progress.newChild(1));
        IProject workspaceProject = CorePlugin.workspaceOperations().includeProject(projectDescription, ImmutableList.<String>of(), progress.newChild(1));
        synchronizeOpenWorkspaceProject(project, workspaceProject, progress.newChild(1));
        return workspaceProject;
    }

    private IProject addNewEclipseProjectToWorkspace(OmniEclipseProject project, SubMonitor progress) throws CoreException {
        progress.setWorkRemaining(3);
        ProjectNameUpdater.ensureProjectNameIsFree(project, this.gradleBuild, progress.newChild(1));
        IProject workspaceProject = CorePlugin.workspaceOperations().createProject(project.getName(), project.getProjectDirectory(), ImmutableList.<String>of(), progress.newChild(1));
        synchronizeOpenWorkspaceProject(project, workspaceProject, progress.newChild(1));
        return workspaceProject;
    }

    private List<IFolder> getNestedSubProjectFolders(OmniEclipseProject project, final IProject workspaceProject) {
        List<IFolder> subProjectFolders = Lists.newArrayList();
        final IPath parentPath = workspaceProject.getLocation();
        for (OmniEclipseProject child : project.getChildren()) {
            IPath childPath = Path.fromOSString(child.getProjectDirectory().getPath());
            if (parentPath.isPrefixOf(childPath)) {
                IPath relativePath = RelativePathUtils.getRelativePath(parentPath, childPath);
                subProjectFolders.add(workspaceProject.getFolder(relativePath));
            }
        }
        return subProjectFolders;
    }

    private void markGradleSpecificFolders(OmniEclipseProject gradleProject, IProject workspaceProject, SubMonitor progress) {
        for (IFolder subProjectFolder : getNestedSubProjectFolders(gradleProject, workspaceProject)) {
            if (subProjectFolder.exists()) {
                CorePlugin.workspaceOperations().markAsSubProject(subProjectFolder);
            }
        }

        List<String> derivedResources = Lists.newArrayList();
        derivedResources.add(".gradle");

        Optional<IFolder> possibleBuildDirectory = getBuildDirectory(gradleProject, workspaceProject);
        if (possibleBuildDirectory.isPresent()) {
            IFolder buildDirectory = possibleBuildDirectory.get();
            derivedResources.add(buildDirectory.getName());
            if (buildDirectory.exists()) {
                CorePlugin.workspaceOperations().markAsBuildFolder(buildDirectory);
            }
        }

        DerivedResourcesUpdater.update(workspaceProject, derivedResources, progress);
    }

    /*
     * If no build directory is available via the TAPI, use 'build'.
     * If build directory is physically contained in the project, use that folder.
     * If build directory is a linked resource, use the linked folder.
     * Optional.absent() if all of the above fail.
     */
    private Optional<IFolder> getBuildDirectory(OmniEclipseProject project, IProject workspaceProject) {
        OmniGradleProject gradleProject = project.getGradleProject();
        Maybe<File> buildDirectory = gradleProject.getBuildDirectory();
        if (buildDirectory.isPresent() && buildDirectory.get() != null) {
            Path buildDirLocation = new Path(buildDirectory.get().getPath());
            return normalizeBuildDirectory(buildDirLocation, workspaceProject, project);
        } else {
            return Optional.of(workspaceProject.getFolder("build"));
        }
    }

    private Optional<IFolder> normalizeBuildDirectory(Path buildDirLocation, IProject workspaceProject, OmniEclipseProject project) {
        IPath projectLocation = workspaceProject.getLocation();
        if (projectLocation.isPrefixOf(buildDirLocation)) {
            IPath relativePath = RelativePathUtils.getRelativePath(projectLocation, buildDirLocation);
            return Optional.of(workspaceProject.getFolder(relativePath));
        } else {
            for (OmniEclipseLinkedResource linkedResource : project.getLinkedResources()) {
                if (buildDirLocation.toString().equals(linkedResource.getLocation())) {
                    return Optional.of(workspaceProject.getFolder(linkedResource.getName()));
                }
            }
            return Optional.absent();
        }
    }

    private boolean isJavaProject(OmniEclipseProject project) {
        return project.getJavaSourceSettings().isPresent();
    }

    private boolean hasJavaNature(IProject project) {
        try {
            return project.hasNature(JavaCore.NATURE_ID);
        } catch (CoreException e) {
            return false;
        }
    }

    private void uncoupleWorkspaceProjectFromGradle(IProject workspaceProject, SubMonitor monitor) {
        monitor.setWorkRemaining(3);
        monitor.subTask(String.format("Uncouple workspace project %s from Gradle", workspaceProject.getName()));
        CorePlugin.workspaceOperations().refreshProject(workspaceProject, monitor.newChild(1, SubMonitor.SUPPRESS_ALL_LABELS));
        CorePlugin.workspaceOperations().removeNature(workspaceProject, GradleProjectNature.ID, monitor.newChild(1, SubMonitor.SUPPRESS_ALL_LABELS));
        DerivedResourcesUpdater.clear(workspaceProject, monitor.newChild(1, SubMonitor.SUPPRESS_ALL_LABELS));
        CorePlugin.projectConfigurationManager().deleteProjectConfiguration(workspaceProject);
    }

    private List<OmniEclipseProject> getAllGradleProjects() {
        return this.gradleBuild.getRootEclipseProject().getAll();
    }

}
