/** Read-only compatibility probe. No application boot, database, connector changes or AI calls. */
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

async function main() {
  const [documentPath, knowdoRoot, outputPath] = process.argv.slice(2);
  if (!documentPath || !knowdoRoot || !outputPath) throw new Error('Usage: tsx check_knowdo_import.ts document.json /opt/knowdo output.json');
  const moduleAt = (name: string) => import(pathToFileURL(path.join(knowdoRoot, 'packages/connectors/src', name)).href);
  const { importOpenAPI } = await moduleAt('openapi.ts');
  const { supportsSuccess } = await moduleAt('response-contracts.ts');
  const { reviewMetadata } = await moduleAt('document-tools.ts');
  const document = JSON.parse(fs.readFileSync(documentPath, 'utf8'));
  const { tools, warnings } = importOpenAPI(document);
  let checked = 0, accepted = 0;
  const failures: string[] = [], unsupportedBodies: object[] = [];
  for (const tool of tools) {
    const op = document.paths[tool.path][tool.method.toLowerCase()];
    const endpoint = `${tool.method} ${tool.path}`;
    const media = Object.keys(op.requestBody?.content || {});
    if (media.length && !media.includes('application/json')) {
      unsupportedBodies.push({ endpoint, media, mappings: tool.mappings,
        inputSchema: tool.inputSchema, reason: '当前解析器只导入 application/json 请求体；须保留其他媒体类型及其字段' });
    }
    if (op['x-business-success']?.path !== 'code') continue;
    checked++;
    const response = tool.responseContracts?.find((c: any) => c.status === '200' && c.mediaType === 'application/json');
    const quote = response?.schema?.properties?.code?.description || '';
    if (supportsSuccess(tool.responseContracts, { path: 'code', equals: 0 },
      { status: '200', mediaType: 'application/json', field: 'code', quote })) accepted++;
    else failures.push(endpoint);
  }
  const page = tools.find((t: any) => t.path === '/admin-api/system/notify-message/my-page');
  const result = { apiVersion: document.info.version, readOnlySourceProbe: true,
    importedTools: tools.length, commonResultProcessingSuccessChecked: checked, evidenceAccepted: accepted, failures,
    authorizationCompletionIsSeparate: true, connectorConfigurationInspected: false, knowdoApplied: false,
    unsupportedBodies, paginationBeforeReview: page?.inputSchema, paginationSentToReview: page ? reviewMetadata(page).inputSchema : null,
    warnings };
  fs.writeFileSync(outputPath, JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify({ importedTools: tools.length, checked, accepted, failures, unsupportedBodies: unsupportedBodies.length }));
  if (failures.length) process.exitCode = 1;
}
main().catch(error => { console.error(error); process.exitCode = 1; });
