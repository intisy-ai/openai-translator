import { readFileSync } from "node:fs";
import { openaiTranslator } from "./translators.js";

const payloadPath = process.argv[2];
if (!payloadPath) {
  console.error("usage: node dist/driver.js <payload.json>");
  process.exit(1);
}

const wireJson = readFileSync(payloadPath, "utf-8");
const ir = await openaiTranslator.decodeRequest(wireJson);
const roundTripped = await openaiTranslator.encodeRequest(ir);
console.log(JSON.stringify(JSON.parse(roundTripped), null, 2));
