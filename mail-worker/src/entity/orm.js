import { drizzle } from 'drizzle-orm/d1';

export default function orm(c) {
 // SQL parameters contain password hashes and private mail; never log them.
 return drizzle(c.env.db, {logger: false});
}
