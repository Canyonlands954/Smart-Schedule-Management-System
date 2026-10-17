const { createRequire } = require("node:module");
const { join } = require("node:path");
const { existsSync } = require("node:fs");
const { chromium } = createRequire(join(__dirname, "../frontend/package.json"))(
  "playwright",
);
const chrome =
  process.platform === "win32"
    ? join(
        process.env.ProgramFiles || "C:/Program Files",
        "Google/Chrome/Application/chrome.exe",
      )
    : "";
module.exports = {
  chromium,
  executable:
    process.env.PLAYWRIGHT_EXECUTABLE ||
    (chrome && existsSync(chrome) ? chrome : undefined),
};
