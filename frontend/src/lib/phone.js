// Student phone format: exactly 10 digits. Mirrors backend service/Phones.java, which is the
// control — these checks only save a round trip.

export const PHONE_RE = /^[0-9]{10}$/;
export const PHONE_ERROR = "Phone number must be exactly 10 digits.";

// Input handler for a typed field: digits only, max 10. Never use it on pasted CSV — capping
// there turns "+91 99999 99999" into a different, valid-looking number.
export const cleanPhoneInput = (value) => value.replace(/\D/g, "").slice(0, 10);

// Optional field: blank is fine, anything else must be 10 digits.
export const isValidOptionalPhone = (value) => !value || PHONE_RE.test(value);
