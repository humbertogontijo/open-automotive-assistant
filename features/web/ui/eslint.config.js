import js from "@eslint/js";
import globals from "globals";
import lit from "eslint-plugin-lit";
import wc from "eslint-plugin-wc";
import compat from "eslint-plugin-compat";

export default [
  { ignores: ["build/", "node_modules/", "types/"] },
  js.configs.recommended,
  lit.configs["flat/recommended"],
  wc.configs["flat/recommended"],
  compat.configs["flat/recommended"],
  {
    files: ["src/**/*.js"],
    languageOptions: {
      ecmaVersion: 2024,
      sourceType: "module",
      globals: globals.browser,
    },
    rules: {
      // Reactive properties must be declared in `static properties`, never as class fields.
      "lit/no-classfield-shadowing": "error",
      "no-empty": ["error", { allowEmptyCatch: true }],
      "no-unused-vars": ["error", { args: "none", caughtErrors: "none" }],
    },
  },
  {
    files: ["build.mjs", "eslint.config.js", "test/**/*.mjs"],
    languageOptions: { globals: globals.node },
  },
];
