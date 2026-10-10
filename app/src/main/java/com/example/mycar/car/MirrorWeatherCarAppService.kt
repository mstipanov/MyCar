package com.example.mycar.car

/**
 * The weather-category twin of [MirrorCarAppService]. The two are identical in code; only the
 * category on their manifest intent filters differs. [CarCategory.register] enables exactly one of
 * them, because the category is a manifest declaration that cannot be changed at runtime.
 */
class MirrorWeatherCarAppService : MirrorCarAppService()
