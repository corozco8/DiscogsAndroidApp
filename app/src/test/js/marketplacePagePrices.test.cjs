// Exercise the complete production page reader against deterministic DOM fixtures.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/com/example/discogsandroidapp/pricing/MarketplaceConditionPriceProbe.kt'), 'utf8');
const template = source.split('val script = """')[1].split('""".trimIndent()')[0];
function read(rows, { excluded = [], body = '', title = 'Discogs Marketplace', frames = [] } = {}) {
    const script = template
        .replace('${JSONObject(usdRates).toString()}', JSON.stringify({ USD: 1, CAD: 0.8, EUR: 1.1 }))
        .replace('${excludedListingIds.joinToString(prefix = "[", postfix = "]")}', JSON.stringify(excluded));
    return JSON.parse(vm.runInNewContext(script, { document: {
        title, body: { innerText: body },
        querySelectorAll: selector => selector === 'iframe' ? frames : rows
    } }));
}
function listing(id, price, media = 'Very Good (VG)', sleeve = 'Very Good Plus (VG+)') {
    return {
        innerText: `Media: ${media}\nSleeve: ${sleeve}\n${price}\nShipping US$999.00`,
        getAttribute: () => null,
        querySelector: selector => {
            if (selector === 'a[href*="/sell/item/"]') return id == null ? null : { getAttribute: () => `/sell/item/${id}` };
            if (selector.startsWith('.price,')) return { textContent: price, getAttribute: () => null };
            return null;
        }
    };
}
const result = read([
    listing(1, 'US$10.00'), listing(2, 'US$10.00'), listing(2, 'US$10.00'),
    listing(3, 'US$100.00', 'Mint (M)', 'No Cover'),
    listing(4, 'CA$20.00', 'Not Graded'), listing(5, 'unreadable')
]);
assert.deepEqual(result.firstPagePrices, [10, 10, 100, 16]);
assert.equal(result.media['Very Good (VG)'], 10);
assert.equal(result.mediaCounts['Very Good (VG)'], 2);
assert.equal(result.media['Mint (M)'], 100);
assert.equal(result.firstPagePrices.reduce((a, b) => a + b, 0) / result.firstPagePrices.length, 34);
const capped = read(Array.from({ length: 260 }, (_, index) => listing(index + 1, `USD ${index + 1}`)));
assert.equal(capped.firstPagePrices.length, 250);
assert.equal(Math.max(...capped.firstPagePrices), 250);
const unreadable = read([listing(1, 'invalid'), ...Array.from({ length: 250 }, (_, index) => listing(index + 2, 'USD 10'))]);
assert.equal(unreadable.firstPagePrices.length, 249);
assert.deepEqual(read([listing(1, 'USD 10'), listing(2, 'USD 20')], { excluded: [1] }).firstPagePrices, [20]);
assert.deepEqual(read([listing(1, 'US$10 + US$5 shipping')]).firstPagePrices, []);
assert.deepEqual(read([listing(1, 'XYZ 10')]).firstPagePrices, []);
assert.equal(read([], { body: 'No copies for sale' }).emptyConfirmed, true);
const challenge = read([], { title: 'Just a moment…', body: 'Cloudflare: Verify you are human' });
assert.equal(challenge.pageTitle, 'Just a moment…');
assert.ok(challenge.pageText.includes('Cloudflare'));
console.log('Production page reader: 14 checks passed');
