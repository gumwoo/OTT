import { useQuery } from '@tanstack/react-query';
import { Link, NavLink, Outlet, useMatch } from 'react-router';
import { meQueryOptions } from '../features/auth/api';

export function RootLayout() {
  const watching = useMatch('/contents/:id/watch');
  return (
    <>
      {!watching && <Header />}
      <Outlet />
    </>
  );
}

function Header() {
  const { data: account } = useQuery(meQueryOptions());
  const navClass = ({ isActive }: { isActive: boolean }) =>
    isActive ? 'text-text' : 'text-text-muted hover:text-text';

  return (
    <header className="bg-bg sticky top-0 z-10 flex h-14 items-center gap-8 px-6 md:px-12">
      <Link to="/" className="text-xl font-bold tracking-tight">
        Stillcut
      </Link>
      {account && (
        <nav className="flex flex-1 items-center gap-5 text-sm">
          <NavLink to="/" end className={navClass}>
            홈
          </NavLink>
          <NavLink to="/account" className={navClass}>
            계정
          </NavLink>
          <span className="text-text-muted ml-auto text-xs">{account.subscription.plan}</span>
        </nav>
      )}
    </header>
  );
}
