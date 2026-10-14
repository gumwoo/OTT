import { redirect, type MiddlewareFunction } from 'react-router';
import { queryClient } from '../../app/queryClient';
import { meQueryOptions } from './api';

export const requireAccount: MiddlewareFunction = async () => {
  const account = await queryClient.ensureQueryData(meQueryOptions());
  if (!account) {
    throw redirect('/login');
  }
};
