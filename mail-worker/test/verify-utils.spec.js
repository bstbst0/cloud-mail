import { describe, it, expect } from 'vitest';
import verifyUtils from '../src/utils/verify-utils.js';

describe('verifyUtils', () => {
	describe('isDomain', () => {
		it('should return true for valid domains', () => {
			expect(verifyUtils.isDomain('example.com')).toBe(true);
			expect(verifyUtils.isDomain('sub.example.com')).toBe(true);
			expect(verifyUtils.isDomain('my-domain.org')).toBe(true);
			expect(verifyUtils.isDomain('a.b.c.co.uk')).toBe(true);
			expect(verifyUtils.isDomain('123.abc')).toBe(true);
		});

		it('should return false for invalid domains', () => {
			expect(verifyUtils.isDomain('example')).toBe(false);
			expect(verifyUtils.isDomain('.com')).toBe(false);
			expect(verifyUtils.isDomain('example.')).toBe(false);
			expect(verifyUtils.isDomain('example.c')).toBe(false);
			expect(verifyUtils.isDomain('123.456.789')).toBe(false);
		});

		it('should return false for domains with protocol or other schemes', () => {
			expect(verifyUtils.isDomain('http://example.com')).toBe(false);
			expect(verifyUtils.isDomain('https://example.com')).toBe(false);
			expect(verifyUtils.isDomain('://example.com')).toBe(false);
		});

		it('should return false for emails', () => {
			expect(verifyUtils.isDomain('user@example.com')).toBe(false);
		});
	});

	describe('isEmail', () => {
		it('should return true for valid emails', () => {
			expect(verifyUtils.isEmail('user@example.com')).toBe(true);
			expect(verifyUtils.isEmail('test.user@sub.example.com')).toBe(true);
			expect(verifyUtils.isEmail('user+alias@example.com')).toBe(true);
			expect(verifyUtils.isEmail('123@abc.com')).toBe(true);
		});

		it('should return false for invalid emails', () => {
			expect(verifyUtils.isEmail('user@')).toBe(false);
			expect(verifyUtils.isEmail('@example.com')).toBe(false);
			expect(verifyUtils.isEmail('user@example')).toBe(false);
			expect(verifyUtils.isEmail('user.example.com')).toBe(false);
		});
	});
});
