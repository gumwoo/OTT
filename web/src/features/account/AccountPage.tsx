import { useMutation, useSuspenseQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { queryClient } from '../../app/queryClient';
import { messageFor } from '../../lib/api/messages';
import { authKeys, changePlan, logout, meQueryOptions, type Plan } from '../auth/api';
import { catalogKeys } from '../catalog/api';

const DATE = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'long' });

export function AccountPage() {
  const navigate = useNavigate();
  const { data: account } = useSuspenseQuery(meQueryOptions());
  const change = useMutation({
    mutationFn: (plan: Plan) => changePlan(plan),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: authKeys.me });
      await queryClient.invalidateQueries({ queryKey: catalogKeys.all });
    },
  });
  const signOut = useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      queryClient.clear();
      await navigate('/login');
    },
  });

  if (!account) {
    return null;
  }
  const { plan, expiresAt } = account.subscription;
  const next: Plan = plan === 'PREMIUM' ? 'BASIC' : 'PREMIUM';

  return (
    <main className="max-w-2xl px-6 py-10 md:px-12">
      <h1 className="text-2xl font-bold">계정</h1>
      <dl className="divide-line border-line mt-8 divide-y border-y">
        <div className="flex justify-between py-4">
          <dt className="text-text-muted">이메일</dt>
          <dd>{account.email}</dd>
        </div>
        <div className="flex justify-between py-4">
          <dt className="text-text-muted">구독</dt>
          <dd className="text-right">
            {plan}
            {expiresAt && (
              <span className="text-text-muted block text-sm">
                {DATE.format(new Date(expiresAt))}까지
              </span>
            )}
          </dd>
        </div>
      </dl>
      <p className="text-text-muted mt-6 text-sm">
        모의 구독입니다. 실제 결제는 일어나지 않고, PREMIUM은 30일 동안 유지됩니다.
      </p>
      <div className="mt-4 flex gap-2">
        <button
          type="button"
          onClick={() => change.mutate(next)}
          disabled={change.isPending}
          className="bg-accent text-bg rounded-sm px-5 py-2 font-semibold disabled:opacity-60"
        >
          {next}으로 바꾸기
        </button>
        <button
          type="button"
          onClick={() => signOut.mutate()}
          disabled={signOut.isPending}
          className="bg-surface-raised text-text hover:bg-line rounded-sm px-5 py-2 disabled:opacity-60"
        >
          로그아웃
        </button>
      </div>
      {(change.isError || signOut.isError) && (
        <p role="alert" className="text-danger mt-4 text-sm">
          {messageFor(change.error ?? signOut.error)}
        </p>
      )}
    </main>
  );
}
