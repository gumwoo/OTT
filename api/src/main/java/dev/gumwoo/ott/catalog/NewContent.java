package dev.gumwoo.ott.catalog;

import dev.gumwoo.ott.subscription.Plan;

public record NewContent(String id, String title, String description, Plan requiredPlan, int durationSec) {

}
