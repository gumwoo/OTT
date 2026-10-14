package dev.gumwoo.ott.catalog;

import dev.gumwoo.ott.subscription.Plan;

public record PlayableContent(String id, Plan requiredPlan, Integer currentVersion) {

	public boolean published() {
		return this.currentVersion != null;
	}

}
