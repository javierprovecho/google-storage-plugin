/*
 * Copyright 2013 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.jenkins.plugins.storage;

import java.io.IOException;
import java.util.Arrays;

import javax.annotation.Nullable;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.jenkins.plugins.util.Resolve;

import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import hudson.util.FormValidation;

/**
 * This upload extension implements the classical upload pattern
 * where a user provides an Ant-style glob, e.g. ** / *.java
 * relative to the build workspace, and those files are uploaded
 * to the storage bucket.
 */
public class ClassicUpload extends AbstractUpload {
  /**
   * Construct the classic upload implementation from the base properties
   * and the glob for matching files.
   */
  @DataBoundConstructor
  public ClassicUpload(String bucketNameWithVars, boolean sharedPublicly,
      boolean forFailedJobs, @Nullable UploadModule module,
      String sourceGlobWithVars) {
    super(bucketNameWithVars, sharedPublicly, forFailedJobs, module);
    this.sourceGlobWithVars = checkNotNull(sourceGlobWithVars);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getDetails() {
    return getSourceGlobWithVars();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @Nullable
  protected UploadSpec getInclusions(AbstractBuild<?, ?> build,
      FilePath workspace, TaskListener listener) throws UploadException {
    try {
      String globResolvedVars = Util.replaceMacro(
          getSourceGlobWithVars(), build.getEnvironment(listener));
      // In order to support absolute globs (e.g. /gagent/metaOutput/std*.txt)
      // we must identify absolute paths and rebase the "workspace" to be the
      // root directory and the glob to be relative to root.
      //
      // NOTE: FilePath#list complains when the ant-style glob is actually
      // an absolute path.
      //
      // TODO(mattmoor): This will not work on Windows, but short of relying
      // on Java 7's java.nio.file.Paths there doesn't appear to be a good
      // platform agnostic approach to this.
      if (globResolvedVars.startsWith("/")) {
        workspace = getRoot(workspace);
        // TODO(mattmoor): Support Windows
        // Drop the leading "/" in a fashion that should be compatible with
        // non-Unix platforms (e.g. Windows, which uses drive letters: C:\)
        globResolvedVars = globResolvedVars.substring(1);
      }

      FilePath[] inclusions = workspace.list(globResolvedVars);
      if (inclusions.length == 0) {
        listener.error(module.prefix(
            Messages.ClassicUpload_NoArtifacts(
                globResolvedVars)));
        return null;
      }
      listener.getLogger().println(module.prefix(
          Messages.ClassicUpload_FoundForPattern(
              inclusions.length, getSourceGlobWithVars())));
      return new UploadSpec(workspace, Arrays.asList(inclusions));
    } catch (InterruptedException e) {
      throw new UploadException(Messages.AbstractUpload_IncludeException(), e);
    } catch (IOException e) {
      throw new UploadException(Messages.AbstractUpload_IncludeException(), e);
    }
  }



  /**
   * Iterate from the workspace through parent directories to its root.
   */
  private FilePath getRoot(final FilePath workspace) {
    FilePath iter = workspace;
    while (iter.getParent() != null) {
      iter = iter.getParent();
    }
    return iter;
  }

  /**
   * The glob of files to upload, which potentially contains unresolved
   * symbols, such as $JOB_NAME and $BUILD_NUMBER.
   */
  public String getSourceGlobWithVars() {
    return sourceGlobWithVars;
  }
  private final String sourceGlobWithVars;


  /**
   * Denotes this is an {@link AbstractUpload} plugin
   */
  @Extension
  public static class DescriptorImpl extends AbstractUploadDescriptor {
    public DescriptorImpl() {
      this(ClassicUpload.class);
    }

    public DescriptorImpl(
      Class<? extends ClassicUpload> clazz) {
      super(clazz);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDisplayName() {
      return Messages.ClassicUpload_DisplayName();
    }

    /**
     * This callback validates the {@code sourceGlobWithVars} input field's
     * values.
     */
    public FormValidation doCheckSourceGlobWithVars(
        @QueryParameter final String sourceGlobWithVars)
        throws IOException {
      String resolvedInput = Resolve.resolveBuiltin(sourceGlobWithVars);
      if (resolvedInput.isEmpty()) {
        return FormValidation.error(
            Messages.ClassicUpload_EmptyGlob());
      }

      if (resolvedInput.contains("$")) {
        // resolved file name still contains variable marker
        return FormValidation.error(
            Messages.ClassicUpload_BadGlobChar("$",
                Messages.AbstractUploadDescriptor_DollarSuggest()));
      }
      // TODO(mattmoor): Validation:
      //  - relative path from workspace
      //  - Ant mask
      //
      // TODO(mattmoor): The Ant glob validation is lackluster in that it
      // requires a FilePath with which to validate the glob.  Consider
      // putting together a regular expression to match valid patterns.
      //
      // NOTE: This side of things must work well with windows backward
      // slashes.
      return FormValidation.ok();
    }
  }
}
