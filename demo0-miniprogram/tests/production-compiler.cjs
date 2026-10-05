const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const ts = require('../node_modules/typescript');

const projectRoot = path.resolve(__dirname, '..');

/** Compile a production TS entry and its local runtime dependencies in isolation. */
function compileProduction(entry) {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'demo0-production-'));
  const seen = new Set();

  function resolveLocal(baseFile, specifier) {
    if (!specifier.startsWith('.')) {
      return null;
    }
    const base = path.resolve(path.dirname(baseFile), specifier);
    const candidates = [
      base,
      `${base}.ts`,
      `${base}.js`,
      path.join(base, 'index.ts'),
      path.join(base, 'index.js'),
    ];
    return candidates.find((candidate) => fs.existsSync(candidate) && fs.statSync(candidate).isFile()) || null;
  }

  function copyOrCompile(sourceFile) {
    const relative = path.relative(projectRoot, sourceFile);
    const outputRelative = relative.endsWith('.ts')
      ? relative.slice(0, -3) + '.js'
      : relative;
    const outputFile = path.join(tempRoot, outputRelative);
    if (seen.has(sourceFile)) {
      return outputFile;
    }
    seen.add(sourceFile);
    fs.mkdirSync(path.dirname(outputFile), { recursive: true });

    const source = fs.readFileSync(sourceFile, 'utf8');
    if (sourceFile.endsWith('.ts')) {
      const compiled = ts.transpileModule(source, {
        compilerOptions: {
          target: ts.ScriptTarget.ES2020,
          module: ts.ModuleKind.CommonJS,
          esModuleInterop: true,
        },
        fileName: sourceFile,
      }).outputText;
      fs.writeFileSync(outputFile, compiled, 'utf8');

      const importPattern = /(?:from\s+|import\s*\(\s*|require\(\s*)['"]([^'"]+)['"]/g;
      let match;
      while ((match = importPattern.exec(source)) !== null) {
        const dependency = resolveLocal(sourceFile, match[1]);
        if (dependency) {
          copyOrCompile(dependency);
        }
      }
    } else {
      fs.copyFileSync(sourceFile, outputFile);
    }
    return outputFile;
  }

  return {
    entry: copyOrCompile(path.resolve(projectRoot, entry)),
    cleanup: () => fs.rmSync(tempRoot, { recursive: true, force: true }),
  };
}

module.exports = { compileProduction };
