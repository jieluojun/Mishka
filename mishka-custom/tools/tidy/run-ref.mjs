import { readFileSync } from 'node:fs';
import { tidyMihomoConfig } from './tidy-ref.mjs';
process.stdout.write(tidyMihomoConfig(readFileSync(process.argv[2], 'utf8')));
