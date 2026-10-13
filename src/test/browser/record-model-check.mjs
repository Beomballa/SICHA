import {
  parseRecordPage,
  parseRecord,
  parseRecordReport,
} from "../../main/resources/static/player-common/game-model.mjs";
const chunks = [];
for await (const chunk of process.stdin) chunks.push(chunk);
try {
  const input = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  const page = parseRecordPage(input.page);
  const detail = parseRecord(input.detail, true, input.recordKey);
  const summary = detail.reports.find(
    (item) => item.reportKey === input.report.reportKey,
  );
  if (
    !summary ||
    !page.items.some((item) => item.recordKey === detail.recordKey)
  )
    throw new Error("Invalid server record binding");
  parseRecordReport(input.report, summary);
  process.stdout.write(JSON.stringify({ ok: true }));
} catch (_) {
  // 실제 합성 서버 응답/보고서/자격 원문을 진단으로 출력하지 않는다.
  process.stderr.write("RECORD_MODEL_CONTRACT_FAILED");
  process.exitCode = 1;
}
