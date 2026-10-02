// Student email rule. Mirrors backend service/Emails.java, which is the control — these checks
// only save a round trip. Any domain is valid; blank means "reset to the college address".

export const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const EMAIL_MAX = 254;
export const EMAIL_ERROR = "Enter a valid email address.";
export const EMAIL_MISMATCH = "The two email addresses don't match.";

export const institutionalEmail = (rollNo) => `${String(rollNo).toLowerCase()}@msrit.edu`;

export const isValidEmail = (value) => value.length <= EMAIL_MAX && EMAIL_RE.test(value);
