package dev.gumwoo.ott.subscription;

public enum Plan {

	BASIC, PREMIUM;

	public boolean covers(Plan required) {
		return compareTo(required) >= 0;
	}

}
