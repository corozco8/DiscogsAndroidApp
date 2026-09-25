// Run with Node; exercises the actual JavaScript embedded in the Kotlin probe.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname, '../../main/java/com/example/discogsandroidapp/MarketplaceConditionPriceProbe.kt'), 'utf8');
const parser = source.slice(source.indexOf('            function parseUsdPrice('), source.indexOf('            function canonicalGrade('));
assert.ok(parser.length > 500);
const context = { USD_RATES: { USD: 1, CAD: 0.8, AUD: 0.65, NZD: 0.6, EUR: 1.1, GBP: 1.3, JPY: 0.007, CHF: 1.2, MXN: 0.05, BRL: 0.2 } };
vm.createContext(context);
vm.runInContext(parser, context);
const parse = context.parseUsdPrice;
const valid = [
    ['US$10.00', '', 10], ['USD 1,234.56', '', 1234.56], ['10.00 USD', '', 10],
    ['CA$10.00', '', 8], ['A$10', '', 6.5], ['NZ$10', '', 6],
    ['€10.00', '', 11], ['£10', '', 13], ['CHF 10', '', 12],
    ['EUR 1.234,56', '', 1358.016], ['EUR 1 234,56', '', 1358.016],
    ['€12,50', '', 13.75], ['JPY 1000', '', 7], ['$10', 'CAD', 8],
    ['¥1000', 'JPY', 7], ['MX$100', '', 5], ['R$100', '', 20]
];
for (const [raw, hint, expected] of valid) assert.ok(Math.abs(parse(raw, hint) - expected) < 1e-8, raw);
for (const raw of ['$10', '¥1000', 'kr 10', 'XYZ 10', 'USD -1', 'USD 0', 'USD 12.3456', 'USD 1,23,4', 'US$10 + $5 shipping', 'total USD 10', '10']) assert.equal(parse(raw, ''), null, raw);
assert.equal(parse('CA$10', 'USD'), null);
assert.equal(parse('EUR 10', 'GBP'), null);
// Compare normalized prices, not nominal foreign amounts.
assert.equal(Math.min(parse('US$10', ''), parse('CA$11', '')), 8.8);
assert.ok(!source.includes('"&currency=USD"'));
console.log(`Currency parser: ${valid.length + 15} checks passed`);
