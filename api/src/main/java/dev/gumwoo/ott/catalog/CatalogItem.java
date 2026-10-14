package dev.gumwoo.ott.catalog;

import dev.gumwoo.ott.subscription.Plan;

public record CatalogItem(String id, String title, Plan requiredPlan, int durationSec) {
}
