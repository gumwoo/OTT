import js from '@eslint/js';
import query from '@tanstack/eslint-plugin-query';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import testingLibrary from 'eslint-plugin-testing-library';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'coverage'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommendedTypeChecked,
      reactHooks.configs.flat.recommended,
      jsxA11y.flatConfigs.recommended,
      ...query.configs['flat/recommended-strict'],
    ],
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // FE-05
      'no-restricted-globals': [
        'error',
        { name: 'fetch', message: 'lib/api/client.ts를 거친다 (FE-05)' },
      ],
      'no-restricted-properties': [
        'error',
        { object: 'window', property: 'fetch', message: 'lib/api/client.ts를 거친다 (FE-05)' },
        { object: 'globalThis', property: 'fetch', message: 'lib/api/client.ts를 거친다 (FE-05)' },
        {
          object: 'navigator',
          property: 'sendBeacon',
          message: 'fetch(..., { keepalive: true })를 쓴다 (FE-10)',
        },
      ],
      // FE-08
      'no-restricted-imports': [
        'error',
        { name: 'react-router-dom', message: "'react-router'를 쓴다 (FE-08)" },
      ],
    },
  },
  {
    files: ['src/lib/api/client.ts'],
    rules: { 'no-restricted-globals': 'off' },
  },
  {
    files: ['src/**/*.test.{ts,tsx}'],
    extends: [testingLibrary.configs['flat/react']],
  },
  {
    files: ['*.config.{js,ts}'],
    languageOptions: { globals: globals.node },
  },
);
