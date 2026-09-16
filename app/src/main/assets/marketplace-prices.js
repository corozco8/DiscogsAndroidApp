/* Read-only DOM extraction. Also executed by tools/test-marketplace-prices.cjs. */
(function () {
    const mediaGrades = ['Near Mint (NM or M-)', 'Very Good Plus (VG+)', 'Very Good (VG)',
        'Good Plus (G+)', 'Good (G)', 'Fair (F)', 'Poor (P)', 'Mint (M)'];
    const sleeveGrades = mediaGrades.concat(['Not Graded', 'No Cover', 'Generic']);
    const vgOrHigherMedia = new Set([
        'Very Good (VG)',
        'Very Good Plus (VG+)',
        'Near Mint (NM or M-)',
        'Mint (M)'
    ]);
    const rejectedSleevesForVgOrHigher = new Set([
        'Fair (F)',
        'Poor (P)',
        'Not Graded',
        'No Cover'
    ]);
    const text = el => el ? (el.innerText || el.textContent || '').trim() : '';
    function grade(raw, grades) {
        return grades.filter(g => raw.includes(g)).sort((a, b) => raw.indexOf(a) - raw.indexOf(b))[0] || '';
    }
    function condition(row, label, grades, selector) {
        const match = text(row).match(new RegExp('\\b' + label + '(?:\\s+Condition)?\\s*:\\s*([^\\n]+)', 'i'));
        return grade(match ? match[1] : text(row.querySelector(selector)), grades);
    }
    function usd(raw) {
        // Require explicit USD. Bare $, other dollars, shipping totals and comma decimals are ambiguous.
        const match = raw.trim().replace(/^about\s+/i, '').match(/^(?:(?:US\$|USD)\s*((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{2})?)|((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{2})?)\s*USD)$/i);
        if (!match) return null;
        const value = Number((match[1] || match[2]).replace(/,/g, ''));
        return Number.isFinite(value) && value > 0 ? value : null;
    }
    function price(row) {
        // Item-price leaves only: never search all row text, which includes shipping and totals.
        const nodes = row.querySelectorAll('.item_price .price, .item_price .converted_price, [data-testid="item-price"], [data-testid="price"]');
        for (const node of nodes) {
            const value = usd(text(node));
            if (value !== null) return value;
        }
        return null;
    }
    const rows = Array.from(new Set(document.querySelectorAll(
        'tr.shortcut_navigable, table.table_block tbody tr, [data-testid="listing-card"], [data-testid="listing-row"]'
    ))).filter(row => row.querySelector('a[href*="/sell/item/"], [data-testid="media-condition"], .item_condition'));
    const body = text(document.body);
    const blocked = /verify you are human|checking your browser|access denied|just a moment|too many requests/i.test(document.title + '\n' + body.slice(0, 2000));
    const empty = Array.from(document.querySelectorAll('.no_results, .empty_state, [data-testid="no-results"], #pjax_container'))
        .some(el => /^(?:no items found|no items for sale|there are no items for sale|no results found)[.!]?$/i.test(text(el)));
    const next = document.querySelector('a[rel="next"], a.pagination_next, [data-testid="pagination-next"]');
    const nextEnabled = next && !next.classList.contains('disabled') && next.getAttribute('aria-disabled') !== 'true' && !!next.getAttribute('href');
    let total = null;
    for (const el of document.querySelectorAll('.pagination_total, .pagination_top, .pagination_bottom, [data-testid="pagination"]')) {
        const match = text(el).match(/\bof\s+([\d,]+)\b/i);
        if (match) { total = Number(match[1].replace(/,/g, '')); break; }
    }
    const url = new URL(location.href);
    const page = Number(url.searchParams.get('page') || '1');
    const limit = Number(url.searchParams.get('limit') || '250');
    let hasNext = nextEnabled ? true : null;
    if (total !== null) hasNext = page * limit < total;
    else if (next && !nextEnabled) hasNext = false;
    else if (empty) hasNext = false;
    const result = {media: {}, pairs: {}, ids: [], rowCount: rows.length, parsedCount: 0,
        hasNext, empty, blocked, ready: document.readyState === 'complete', total};
    const min = (map, key, value) => { map[key] = Math.min(map[key] === undefined ? Infinity : map[key], value); };
    for (const row of rows) {
        const link = row.querySelector('a[href*="/sell/item/"]');
        const id = link && (link.getAttribute('href') || '').match(/\/sell\/item\/(\d+)/);
        if (id) result.ids.push(id[1]);
        const media = condition(row, 'Media', mediaGrades, '[data-testid="media-condition"], .item_condition');
        const sleeve = condition(row, 'Sleeve', sleeveGrades, '[data-testid="sleeve-condition"], .item_sleeve_condition');
        const value = price(row);
        if (!media || !sleeve || value === null) continue;
        result.parsedCount++;

        // Preserve every exact media+sleeve pair. If the seller explicitly
        // chooses F, P, No Cover, or Not Graded with VG-or-better media, the
        // app can use this true like-for-like live price for that one choice.
        min(result.pairs, media + '||' + sleeve, value);

        // For the normal media-grade recommendation, VG or better ignores
        // severely compromised/missing sleeves and moves to the next listing.
        if (vgOrHigherMedia.has(media) && rejectedSleevesForVgOrHigher.has(sleeve)) {
            continue;
        }

        min(result.media, media, value);
    }
    return JSON.stringify(result);
})();
