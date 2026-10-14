import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useMatch } from 'react-router';
import { meQueryOptions } from '../features/auth/api';

export function RootLayout() {
  const watching = useMatch('/contents/:id/watch');
  if (watching) {
    return <Outlet />;
  }
  return (
    <div className="flex min-h-dvh flex-col">
      <Header />
      <div className="flex flex-1 flex-col">
        <Outlet />
      </div>
      <Footer />
    </div>
  );
}

// 히어로 위에서는 이미지가 보이게 투명하게 두고, 내려가면 바탕색으로 덮는다
function Header() {
  const { data: account } = useQuery(meQueryOptions());
  const scrolled = useScrolled(8);
  const navClass = ({ isActive }: { isActive: boolean }) =>
    isActive ? 'text-text font-semibold' : 'text-text-muted hover:text-text';

  return (
    <header
      className={`fixed inset-x-0 top-0 z-20 transition-colors duration-300 ${scrolled ? 'bg-bg' : 'scrim-top'}`}
    >
      <div className="flex h-14 items-center gap-10 px-6 md:px-10">
        <Link to="/" className="text-xl font-bold tracking-tight">
          Stillcut
        </Link>
        {account && (
          <nav className="flex flex-1 items-center gap-6 text-sm">
            <NavLink to="/" end className={navClass}>
              홈
            </NavLink>
            <NavLink to="/account" className={navClass}>
              계정
            </NavLink>
            <span className="text-text-muted ml-auto text-xs tracking-wide">
              {account.subscription.plan}
            </span>
          </nav>
        )}
      </div>
    </header>
  );
}

function Footer() {
  return (
    <footer className="text-text-muted border-line mt-16 border-t px-6 py-8 text-xs md:px-10">
      <p>
        작품 영상과 이미지는 Blender Foundation의 오픈 무비(CC BY)입니다.{' '}
        <Link to="/credits" className="hover:text-text underline underline-offset-4">
          작품 출처
        </Link>
      </p>
    </footer>
  );
}

function useScrolled(threshold: number): boolean {
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const update = () => setScrolled(window.scrollY > threshold);
    update();
    window.addEventListener('scroll', update, { passive: true });
    return () => window.removeEventListener('scroll', update);
  }, [threshold]);
  return scrolled;
}
