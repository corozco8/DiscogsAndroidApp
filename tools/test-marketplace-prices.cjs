// Offline parser regression tests. Minimal DOM doubles isolate extraction; no HTTP calls.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/marketplace-prices.js'), 'utf8');
const vg = 'Very Good (VG)', vgp = 'Very Good Plus (VG+)', nm = 'Near Mint (NM or M-)', mint = 'Mint (M)', g = 'Good (G)', gp = 'Good Plus (G+)', f = 'Fair (F)', p = 'Poor (P)';
const el = text => ({innerText: text, textContent: text});
let listingId = 0;
function row(media, sleeve, prices, extra = '') {
    const id = ++listingId;
    return {...el(`Media: ${media}\nSleeve: ${sleeve}\n${extra}`),
        querySelector: selector => selector.includes('/sell/item/') ? {...el('listing'), getAttribute: () => '/sell/item/' + id} : null,
        querySelectorAll: () => prices.map(el)};
}
function run(rows, {total = rows.length, next = null, empty = false, title = '', page = 1} = {}) {
    const document = {
        title, readyState: 'complete', body: el(title),
        querySelector: () => next,
        querySelectorAll: selector => {
            if (selector.includes('tr.shortcut')) return rows;
            if (selector.includes('.pagination_total')) return total === null ? [] : [el(`1 – 250 of ${total.toLocaleString('en-US')}`)];
            if (selector.includes('.no_results')) return empty ? [el('No items for sale.')] : [];
            return [];
        }
    };
    return JSON.parse(vm.runInNewContext(script, {document, URL, location: {href: `https://www.discogs.com/sell/list?page=${page}&limit=250`}}));
}
let checks = 0;
function test(name, fn) { fn(); checks++; console.log('PASS ' + name); }
test('media and sleeve stay distinct; minimum only among matching rows', () => {
    const r = run([row(g, gp, ['US$20.00']), row(g, gp, ['US$18.00']), row(g, nm, ['USD 30.00'])]);
    assert.equal(r.media[g], 18); assert.equal(r.pairs[g + '||' + nm], 30); assert.equal(r.media[gp], undefined);
});
test('VG or higher skips bad sleeves for generic media price but preserves exact pairs', () => {
    for (const media of [vg, vgp, nm, mint]) {
        for (const badSleeve of [f, p, 'No Cover', 'Not Graded']) {
            const r = run([
                row(media, badSleeve, ['US$10.00']),
                row(media, vg, ['US$18.00'])
            ]);
            assert.equal(r.parsedCount, 2, `${media} / ${badSleeve} remains a parsed row`);
            assert.equal(r.media[media], 18, `${media} / ${badSleeve} must not set the live minimum`);
            assert.equal(r.pairs[media + '||' + badSleeve], 10, `${media} / ${badSleeve} exact pair stays available when seller explicitly selects it`);
        }
    }
});
test('below VG keeps low-sleeve listings eligible', () => {
    const r = run([row(gp, f, ['US$10.00']), row(gp, vg, ['US$18.00'])]);
    assert.equal(r.media[gp], 10);
    assert.equal(r.pairs[gp + '||' + f], 10);
});
test('strict USD currency and number parsing', () => {
    for (const price of ['€10.00', '£10.00', 'CA$10.00', 'AU$10.00', '$10.00', 'US$10,50', 'US$1.234,56', 'US$12.345', 'US$0.00', 'US$-4.00', 'US$10.00 + US$5.00 shipping']) {
        assert.equal(run([row(vg, nm, [price])]).parsedCount, 0, price);
    }
    for (const price of ['US$1,234.56', 'USD 1234.56', '1234.56 USD', 'about US$1,234.56']) {
        assert.equal(run([row(vg, nm, [price])]).media[vg], 1234.56);
    }
});
test('shipping in row text cannot become item price', () => {
    assert.equal(run([row(vg, nm, [], 'Shipping US$2.00; total US$22.00')]).parsedCount, 0);
});
test('unknown sleeve fails completeness instead of claiming no match', () => {
    const r = run([row(vg, 'Unknown grade', ['US$12.00'])]);
    assert.equal(r.rowCount, 1); assert.equal(r.parsedCount, 0);
});
test('first 250 of 251 needs another page', () => {
    assert.equal(run([], {total: 251}).hasNext, true);
    assert.equal(run([], {total: 251, page: 2}).hasNext, false);
});
test('unknown pagination cannot claim exhaustion', () => {
    assert.equal(run([row(vg, nm, ['US$10.00'])], {total: null}).hasNext, null);
});
test('explicit empty differs from a blank loading page', () => {
    assert.equal(run([], {total: null, empty: true}).empty, true);
    assert.equal(run([], {total: null, empty: true}).hasNext, false);
    assert.equal(run([], {total: null}).empty, false);
});
test('challenge pages are reported as blocked', () => {
    assert.equal(run([], {title: 'Just a moment… verify you are human'}).blocked, true);
});
test('duplicate DOM references do not double count listings', () => {
    const r = row(vg, nm, ['US$10.00']); assert.equal(run([r, r]).rowCount, 1);
});
console.log(`${checks} parser checks passed; no network used.`);
