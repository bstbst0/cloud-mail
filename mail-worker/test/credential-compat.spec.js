import {describe,expect,it} from 'vitest';
import cryptoUtils from '../src/utils/crypto-utils';

describe('Password hash migration compatibility',()=>{
 it('verifies existing salted SHA-256 passwords without forcing a reset',async()=>{
  const salt='synthetic-legacy-salt';
  const hash=await cryptoUtils.genHashPassword('fixture-password',salt);
  expect(await cryptoUtils.verifyPassword('fixture-password',salt,hash)).toBe(true);
  expect(await cryptoUtils.verifyPassword('wrong-password',salt,hash)).toBe(false);
 });
 it('writes independently salted versioned PBKDF2 hashes and rejects wrong passwords',async()=>{
  const first=await cryptoUtils.hashPassword('fixture-password');
  const second=await cryptoUtils.hashPassword('fixture-password');
  expect(first.salt).not.toBe(second.salt);expect(first.hash).not.toBe(second.hash);
  expect(first.hash).toMatch(/^pbkdf2-sha256\$100000\$/);
  expect(await cryptoUtils.verifyPassword('fixture-password',first.salt,first.hash)).toBe(true);
  expect(await cryptoUtils.verifyPassword('wrong-password',first.salt,first.hash)).toBe(false);
 });
});
