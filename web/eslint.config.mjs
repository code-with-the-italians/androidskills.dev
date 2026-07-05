import js from '@eslint/js';
import tsPlugin from '@typescript-eslint/eslint-plugin';
import tsParser from '@typescript-eslint/parser';
import globals from 'globals';

export default [
  {
    ignores: ['.astro/**', 'dist/**', 'node_modules/**'],
  },
  js.configs.recommended,
  {
    languageOptions: {
      globals: { ...globals.node, ...globals.browser, RequestInit: 'readonly' },
    },
    rules: {
      'no-console': 'off',
    },
  },
  {
    files: ['**/*.ts', '**/*.mjs', '**/*.cjs'],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
    },
    plugins: { '@typescript-eslint': tsPlugin },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_' },
      ],
    },
  },
];
