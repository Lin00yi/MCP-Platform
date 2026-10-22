/// <reference types="vite/client" />

/** 构建期注入的控制台版本信息（见 scripts/version.mjs 与 vite.config.js） */
declare const __CONSOLE_VERSION__: string;
declare const __CONSOLE_BUILD_ID__: string;
declare const __CONSOLE_BUILD_TIME__: string;
declare const __CONSOLE_COMMIT__: string;
declare const __CONSOLE_RELEASES__: Array<{
  version: string;
  date?: string;
  highlights: string[];
}>;
