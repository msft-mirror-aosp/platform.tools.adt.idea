load("@rules_pkg//pkg:mappings.bzl", "pkg_files", "strip_prefix")
load("@rules_pkg//pkg:pkg.bzl", "pkg_zip")

# Project templates of the New Project Wizard, rendered by the template engine
# (//tools/base/template-engine). Each sub-directory is a template: a regular project,
# plus a ".template" directory holding its "template-definition.json" and thumbnails.
#
# The templates are plain files and must not be compiled as part of the module, so
# this directory is a separate package and is excluded from the module's source root
# in intellij.android.newProjectWizard.iml.
pkg_zip(
    name = "android-project-templates",
    srcs = [":template_files"],
    visibility = ["//tools/adt/idea/android-npw:__pkg__"],
)

pkg_files(
    name = "template_files",
    srcs = glob(
        ["**"],
        exclude = ["BUILD", "OWNERS"],
    ),
    strip_prefix = strip_prefix.from_pkg(),
)
