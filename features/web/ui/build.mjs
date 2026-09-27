// Builds the SPA into build/dist/web (served at / and /static/ by the car and the hub).
//   node build.mjs           production build: minified, split, hashed, precompressed
//   node build.mjs --watch   development rebuilds (no minify, no precompression)
//   node build.mjs --size    production build plus a per-module size report
import * as esbuild from "esbuild";
import { cp, mkdir, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { brotliCompressSync, constants as zlibConstants, gzipSync } from "node:zlib";

const root = dirname(fileURLToPath(import.meta.url));
const outRoot = join(root, "build/dist/web");
const watch = process.argv.includes("--watch");
const sizeReport = process.argv.includes("--size");

/** Keep in sync with "browserslist" in package.json. */
export const TARGET = ["chrome111", "firefox115", "safari16.4"];
const URL_BASE = "/static/";
const JS_ENTRY = "src/app.js";
const CSS_ENTRY = "src/css/index.css";
const COMPRESSIBLE = /\.(js|css|html|svg|json)$/;
const MIN_COMPRESS_BYTES = 1024;

/** @type {esbuild.BuildOptions} */
const options = {
  absWorkingDir: root,
  // jsconfig.json "paths" only point tsc at type declarations; never bundle through them.
  tsconfigRaw: "{}",
  entryPoints: [
    { in: JS_ENTRY, out: "app" },
    { in: CSS_ENTRY, out: "app" },
  ],
  outdir: outRoot,
  outbase: root,
  bundle: true,
  splitting: true,
  format: "esm",
  target: TARGET,
  minify: !watch,
  sourcemap: watch ? "linked" : false,
  legalComments: watch ? "inline" : "external",
  metafile: true,
  entryNames: "assets/[name]-[hash]",
  chunkNames: "assets/[name]-[hash]",
  assetNames: "assets/[name]-[hash]",
  publicPath: URL_BASE,
  loader: { ".svg": "file", ".woff2": "file", ".png": "file" },
  logLevel: watch ? "info" : "warning",
};

async function copyPublic() {
  await cp(join(root, "public"), outRoot, { recursive: true });
}

/** Output paths (relative to outRoot) for the JS entry, its static imports, and the CSS entry. */
function entryOutputs(metafile) {
  let js = "";
  let css = "";
  /** @type {string[]} */
  let preload = [];
  for (const [out, meta] of Object.entries(metafile.outputs)) {
    const rel = relative(outRoot, join(root, out));
    if (meta.entryPoint === JS_ENTRY && out.endsWith(".js")) {
      js = rel;
      preload = meta.imports
        .filter((i) => i.kind === "import-statement")
        .map((i) => relative(outRoot, join(root, i.path)));
    } else if (meta.entryPoint === CSS_ENTRY && out.endsWith(".css")) {
      css = rel;
    }
  }
  if (!js || !css) throw new Error("entry outputs missing from metafile");
  return { js, css, preload };
}

async function writeIndex(metafile) {
  const { js, css, preload } = entryOutputs(metafile);
  let html = await readFile(join(root, "index.html"), "utf8");
  const links = [`<link rel="stylesheet" href="${URL_BASE}${css}">`]
    .concat(preload.map((p) => `<link rel="modulepreload" href="${URL_BASE}${p}">`))
    .join("\n  ");
  html = html
    .replace(`<link rel="stylesheet" href="./${CSS_ENTRY}">`, links)
    .replace(`<script type="module" src="./${JS_ENTRY}"></script>`, `<script type="module" src="${URL_BASE}${js}"></script>`);
  if (html.includes(`./${CSS_ENTRY}`) || html.includes(`./${JS_ENTRY}`)) {
    throw new Error("index.html entry placeholders not found");
  }
  await writeFile(join(outRoot, "index.html"), html);
}

async function* walk(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(p);
    else yield p;
  }
}

async function precompress() {
  for await (const file of walk(outRoot)) {
    if (!COMPRESSIBLE.test(file)) continue;
    const raw = await readFile(file);
    if (raw.length < MIN_COMPRESS_BYTES) continue;
    await writeFile(`${file}.gz`, gzipSync(raw, { level: 9 }));
    await writeFile(
      `${file}.br`,
      brotliCompressSync(raw, {
        params: {
          [zlibConstants.BROTLI_PARAM_QUALITY]: 11,
          [zlibConstants.BROTLI_PARAM_SIZE_HINT]: raw.length,
        },
      }),
    );
  }
}

const kb = (n) => `${(n / 1024).toFixed(1)} KB`;

async function sizeOf(file) {
  const raw = (await stat(file)).size;
  const read = async (ext) => (await stat(file + ext).catch(() => ({ size: raw }))).size;
  return { raw, gz: await read(".gz"), br: await read(".br") };
}

/** Initial = entry JS + its static imports + CSS; everything else is a lazy chunk. */
async function printSizes(metafile) {
  const { js, css, preload } = entryOutputs(metafile);
  const initial = new Set([js, css, ...preload]);
  const rows = [];
  for (const out of Object.keys(metafile.outputs)) {
    if (!/\.(js|css)$/.test(out)) continue;
    const rel = relative(outRoot, join(root, out));
    rows.push({ rel, initial: initial.has(rel), ...(await sizeOf(join(outRoot, rel))) });
  }
  rows.sort((a, b) => Number(b.initial) - Number(a.initial) || b.raw - a.raw);
  const total = (list) => list.reduce((s, r) => ({ raw: s.raw + r.raw, br: s.br + r.br }), { raw: 0, br: 0 });
  console.log("\nOutput                                            raw        br");
  for (const r of rows) {
    console.log(`${(r.initial ? "* " : "  ") + r.rel.padEnd(46)} ${kb(r.raw).padStart(10)} ${kb(r.br).padStart(10)}`);
  }
  const init = total(rows.filter((r) => r.initial));
  const all = total(rows);
  console.log(`\nInitial load (*): ${kb(init.raw)} raw, ${kb(init.br)} br. All JS/CSS: ${kb(all.raw)} raw, ${kb(all.br)} br.`);
  if (sizeReport) {
    console.log(await esbuild.analyzeMetafile(metafile, { verbose: false }));
  }
}

const indexPlugin = {
  name: "oaa-index",
  setup(build) {
    build.onEnd(async (result) => {
      if (result.errors.length || !result.metafile) return;
      await copyPublic();
      await writeIndex(result.metafile);
    });
  },
};

await rm(outRoot, { recursive: true, force: true });
await mkdir(outRoot, { recursive: true });

if (watch) {
  const ctx = await esbuild.context({ ...options, plugins: [indexPlugin] });
  await ctx.watch();
  console.log(`watching; output in ${relative(process.cwd(), outRoot)} (serve it with OAA_WEB_DIR)`);
} else {
  const result = await esbuild.build(options);
  await copyPublic();
  await writeIndex(result.metafile);
  await precompress();
  await printSizes(result.metafile);
}
