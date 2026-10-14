import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { queryClient } from '../../app/queryClient';
import { messageFor } from '../../lib/api/messages';
import { authKeys, login, signup } from './api';

type Mode = 'login' | 'signup';

const COPY = {
  login: {
    title: '로그인',
    submit: '로그인',
    switchText: '계정이 없으면',
    switchLink: '가입하기',
    switchTo: '/signup',
  },
  signup: {
    title: '가입',
    submit: '가입하기',
    switchText: '이미 계정이 있으면',
    switchLink: '로그인',
    switchTo: '/login',
  },
} as const;

export function AuthForm({ mode }: { mode: Mode }) {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const copy = COPY[mode];
  const submit = useMutation({
    mutationFn: () => (mode === 'login' ? login(email, password) : signup(email, password)),
    onSuccess: async (account) => {
      queryClient.clear();
      queryClient.setQueryData(authKeys.me, account);
      await navigate('/');
    },
  });

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    submit.mutate();
  }

  return (
    <main className="mx-auto mt-24 w-full max-w-sm px-6">
      <h1 className="text-2xl font-bold">{copy.title}</h1>
      <form onSubmit={onSubmit} className="mt-8 flex flex-col gap-4">
        <label className="flex flex-col gap-1 text-sm">
          이메일
          <input
            type="email"
            required
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="border-line bg-surface text-text rounded-sm border px-3 py-2 text-base"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          비밀번호
          <input
            type="password"
            required
            minLength={mode === 'signup' ? 8 : undefined}
            autoComplete={mode === 'signup' ? 'new-password' : 'current-password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="border-line bg-surface text-text rounded-sm border px-3 py-2 text-base"
          />
        </label>
        {submit.isError && (
          <p role="alert" className="text-danger text-sm">
            {messageFor(submit.error)}
          </p>
        )}
        <button
          type="submit"
          disabled={submit.isPending}
          className="bg-accent text-bg mt-2 rounded-sm py-2 font-semibold disabled:opacity-60"
        >
          {copy.submit}
        </button>
      </form>
      <p className="text-text-muted mt-6 text-sm">
        {copy.switchText}{' '}
        <Link to={copy.switchTo} className="text-text underline underline-offset-4">
          {copy.switchLink}
        </Link>
      </p>
    </main>
  );
}
