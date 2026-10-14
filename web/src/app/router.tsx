import { createBrowserRouter, redirect, type RouteObject } from 'react-router';
import { AccountPage } from '../features/account/AccountPage';
import { meQueryOptions } from '../features/auth/api';
import { AuthForm } from '../features/auth/AuthForm';
import { requireAccount } from '../features/auth/requireAccount';
import { catalogQueryOptions, contentQueryOptions } from '../features/catalog/api';
import { CatalogPage } from '../features/catalog/CatalogPage';
import { ContentPage } from '../features/content/ContentPage';
import { CreditsPage } from '../features/credits/CreditsPage';
import { queryClient } from './queryClient';
import { RootLayout } from './RootLayout';
import { LoadingPage } from './LoadingPage';
import { RouteError } from './RouteError';

async function redirectIfSignedIn() {
  const account = await queryClient.ensureQueryData(meQueryOptions());
  return account ? redirect('/') : null;
}

export const routes: RouteObject[] = [
  {
    Component: RootLayout,
    HydrateFallback: LoadingPage,
    ErrorBoundary: RouteError,
    children: [
      { path: 'login', loader: redirectIfSignedIn, element: <AuthForm mode="login" /> },
      { path: 'signup', loader: redirectIfSignedIn, element: <AuthForm mode="signup" /> },
      { path: 'credits', Component: CreditsPage },
      {
        middleware: [requireAccount],
        ErrorBoundary: RouteError,
        children: [
          {
            index: true,
            loader: () => queryClient.ensureQueryData(catalogQueryOptions()),
            Component: CatalogPage,
          },
          {
            path: 'contents/:id',
            loader: ({ params }) =>
              queryClient.ensureQueryData(contentQueryOptions(params.id ?? '')),
            Component: ContentPage,
          },
          {
            path: 'contents/:id/watch',
            // hls.js가 커서 재생 화면에 들어갈 때만 받는다
            lazy: async () => {
              const player = await import('../features/player/PlayerPage');
              return {
                loader: player.playerLoader,
                shouldRevalidate: player.shouldPlayerRevalidate,
                Component: player.PlayerPage,
              };
            },
          },
          { path: 'account', Component: AccountPage },
        ],
      },
    ],
  },
];

export const router = createBrowserRouter(routes);
