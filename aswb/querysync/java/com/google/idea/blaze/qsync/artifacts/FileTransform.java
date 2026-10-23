package com.google.idea.blaze.qsync.artifacts;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.MoreFiles;
import com.google.idea.blaze.common.artifact.CachedArtifact;
import com.google.idea.blaze.exception.BuildException;
import com.google.idea.common.experiments.BoolExperiment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public interface FileTransform {
  ImmutableSet<Path> copyWithTransform(CachedArtifact src, Path dest)
      throws BuildException, IOException;

  BoolExperiment hardlinkArtifacts = new BoolExperiment("qsync.artifact.hardlink.enabled", false);

  FileTransform COPY =
      (src, dest) -> {
        if (hardlinkArtifacts.getValue()) {
          src.createHardLink(dest);
        } else {
          src.byteSource().copyTo(MoreFiles.asByteSink(dest));
        }
        return ImmutableSet.of(dest);
      };

  FileTransform UNZIP = FileTransform::unzip;

  private static ImmutableSet<Path> unzip(CachedArtifact src, Path destination) throws IOException {
    Files.createDirectory(destination);
    ImmutableSet.Builder dests = ImmutableSet.builder();
    try (ZipFile zip = src.openAsZipFile()) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        Path target = resolveEntry(destination, entry);
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          // Srcjars do not contain separate directory entries
          Files.createDirectories(target.getParent());
          try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
          }
          dests.add(target);
        }
      }
    }
    return dests.build();
  }

  /** Resolves {@code entry} against {@code destination}, rejecting names that would escape it. */
  private static Path resolveEntry(Path destination, ZipEntry entry) throws IOException {
    Path normalizedDestination = destination.normalize();
    Path resolved = normalizedDestination.resolve(entry.getName()).normalize();
    if (!resolved.startsWith(normalizedDestination)) {
      throw new IOException(
          String.format("Zip entry '%s' resolves outside of %s", entry.getName(), destination));
    }
    return resolved;
  }
}
