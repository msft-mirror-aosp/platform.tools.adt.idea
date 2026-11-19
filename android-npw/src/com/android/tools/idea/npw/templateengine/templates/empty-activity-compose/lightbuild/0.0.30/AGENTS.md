This project uses the Lightbuild build system. It is configured by `project.lightbuild.yaml` at the
project root and a `lightbuild.yaml` file in each module.

Run `lightbuild skill` before making changes. It prints the instructions for the Lightbuild CLI and
for the Lightbuild version this project pins, including the build file format and every command.

If the `lightbuild` command is not found, install it with the Android CLI (`android`), using the
`lightbuild.version` from `project.lightbuild.yaml` as `<version>`:

1. Download the Lightbuild distribution, which contains the CLI:
   ```shell
   android sdk install build/lightbuild/<version>
   ```
   The CLI is then at `<sdk>/build/lightbuild/<version>/cli/lightbuild` (`lightbuild.exe` on
   Windows), where `<sdk>` is the path printed by `android info sdk`.
2. Run the install script shipped next to the CLI. It copies the CLI to `~/.local/bin`
   (`%USERPROFILE%\AppData\LightbuildCLI` on Windows) and adds that directory to your `PATH`:
  - Linux and macOS:
    ```shell
    bash "$(android info sdk)/build/lightbuild/<version>/cli/install.sh"
    ```
    Then restart the terminal, or `source` the shell profile the script reports.
  - Windows (PowerShell):
    ```powershell
    & "$(android info sdk)\build\lightbuild\<version>\cli\install.cmd"
    ```
  - Windows (Command Prompt):
    ```bat
    for /f "delims=" %p in ('android info sdk') do "%p\build\lightbuild\<version>\cli\install.cmd"
    ```
