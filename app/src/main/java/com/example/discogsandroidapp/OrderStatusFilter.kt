package com.example.discogsandroidapp

internal fun matchesOrderStatus(actual: String?, selected: String): Boolean =
    selected.equals("All", ignoreCase = true) || selected.equals("All Orders", ignoreCase = true) ||
        actual?.trim()?.equals(selected.trim(), ignoreCase = true) == true
