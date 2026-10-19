const encoder = new TextEncoder();

const saltHashUtils = {

	generateSalt(length = 16) {
		const array = new Uint8Array(length);
		crypto.getRandomValues(array);
		return btoa(String.fromCharCode(...array));
	},


	async hashPassword(password) {
		const salt = this.generateSalt();
		const hash = await this.pbkdf2Password(password, salt);
		return { salt, hash };
	},

	async genHashPassword(password, salt) {
		const data = encoder.encode(salt + password);
		const hashBuffer = await crypto.subtle.digest('SHA-256', data);
		const hashArray = Array.from(new Uint8Array(hashBuffer));
		return btoa(String.fromCharCode(...hashArray));
	},

    async pbkdf2Password(password,salt) {
     // Cloudflare Web Crypto supports at most 100,000 PBKDF2 iterations.
     const key=await crypto.subtle.importKey('raw',encoder.encode(password),'PBKDF2',false,['deriveBits']);
     const bits=await crypto.subtle.deriveBits({name:'PBKDF2',hash:'SHA-256',salt:encoder.encode(salt),iterations:100000},key,256);
     return 'pbkdf2-sha256$100000$'+btoa(String.fromCharCode(...new Uint8Array(bits)));
    },
    async verifyPassword(inputPassword,salt,storedHash) {
     if(typeof inputPassword!=='string'||typeof salt!=='string'||typeof storedHash!=='string')return false;
     // Preserve existing accounts: no password reset or silent incompatible hash rewrite.
     const hash=storedHash.startsWith('pbkdf2-sha256$100000$') ? await this.pbkdf2Password(inputPassword,salt) : await this.genHashPassword(inputPassword,salt);
     if(hash.length!==storedHash.length)return false;
     let difference=0;
     for(let i=0;i<hash.length;i++)difference|=hash.charCodeAt(i)^storedHash.charCodeAt(i);
     return difference===0;
    },

	genRandomPwd(length = 8) {
		const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
		let result = '';
		const max = Math.floor(256 / chars.length) * chars.length;
		const buffer = new Uint8Array(length);

		while (result.length < length) {
			crypto.getRandomValues(buffer);
			for (const value of buffer) {
				if (value >= max) continue;
				result += chars.charAt(value % chars.length);
				if (result.length === length) break;
			}
		}
		return result;
	}
};

export default saltHashUtils;
