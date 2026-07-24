import { defineConfig } from "vite";
import { spawnSync } from "child_process";
import { fileURLToPath } from "url";
import { dirname, resolve } from "path";

function isDev() {
  return process.env.NODE_ENV !== "production";
}

// The actual project root -- one directory up from console-tyrian,
// where build.sbt actually lives. Unlike github.com/zetashift/
// tyrian-vite-tailwindcss-example (where vite.config.js and build.sbt
// are siblings, so spawnSync's default cwd was already correct),
// console-tyrian is a subdirectory of llmsim's real sbt root, so an
// explicit cwd is required here -- without it, sbt was being launched
// from inside console-tyrian itself, which has no build.sbt of its own.
const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

// Through some hoops we get the sbt output, this is necessary because
// there are some slight platform specific things required. Pattern
// confirmed working end to end in github.com/zetashift/
// tyrian-vite-tailwindcss-example, adapted for llmsim's project names
// (consoleTyrian, not app) and its subdirectory layout (explicit cwd).
function printSbtTask(task) {
  const args = ["--error", "--batch", `print consoleTyrian/${task}`];
  const options = {
    cwd: projectRoot,
    stdio: [
      "pipe", // StdIn.
      "pipe", // StdOut.
      "inherit", // StdErr.
    ],
  };
  const result =
    process.platform === "win32"
      ? spawnSync(
          "sbt.bat",
          args.map((x) => `"${x}"`),
          { shell: true, ...options }
        )
      : spawnSync("sbt", args, options);

  if (result.error) throw result.error;
  if (result.status !== 0)
    throw new Error(`sbt process failed with exit code ${result.status}`);
  return result.stdout.toString("utf8").trim();
}

// This is a string representing the directory which contains the
// Scala.js output file
const linkOutputDir = isDev()
  ? printSbtTask("fastLinkOutputDir")
  : printSbtTask("fullLinkOutputDir");

export default defineConfig({
  resolve: {
    alias: [
      {
        find: "@linkOutputDir",
        replacement: linkOutputDir,
      },
    ],
  },
});
