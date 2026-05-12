"use client";

import { Client, type IMessage } from "@stomp/stompjs";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import type { RealtimeEvent } from "./types";
import { ToastStack } from "@/components/Toast";
import { useAuth } from "./auth-context";

const WS_URL =
  process.env.NEXT_PUBLIC_WS_URL ??
  (process.env.NODE_ENV === "development" ? "ws://localhost:8080/ws" : "");

/**
 * Per-user destination. The broker rewrites this to /user/{principal}/queue/events and
 * delivers only to the session whose CONNECT frame carried a matching token — so a client
 * physically cannot receive another account's balance or billing events.
 */
const USER_QUEUE = "/user/queue/events";

/**
 * Public market feed. Carries no identity — just "the book moved" — so any signed-in client
 * may subscribe. It is what makes a listing filled by *someone else* disappear from your
 * screen without a manual reload.
 */
const MARKET_TOPIC = "/topic/market";

const MAX_EVENTS = 200;

interface EventsContextValue {
  events: RealtimeEvent[];
  connected: boolean;
  /**
   * Increments whenever the order book changes anywhere on the exchange.
   *
   * <p>Deliberately a counter rather than an entry in {@link events}: market activity is not
   * a personal notification, so it must not appear in the activity feed or raise a toast.
   * Pages that show market data depend on this value to know when to refetch.
   */
  marketVersion: number;
  clear: () => void;
}

const EventsContext = createContext<EventsContextValue>({
  events: [],
  connected: false,
  marketVersion: 0,
  clear: () => {},
});

export function useEvents(): EventsContextValue {
  return useContext(EventsContext);
}

/**
 * Maintains the authenticated WebSocket connection for the signed-in user.
 *
 * <p>The browser WebSocket API cannot set an Authorization header on the handshake, so the
 * access token travels in the STOMP CONNECT frame's headers instead, where the backend's
 * channel interceptor verifies it. A connection is only opened once a session exists, and it
 * is torn down and rebuilt when the token changes — otherwise a refreshed token would leave
 * the socket authenticated by a credential the client no longer holds.
 */
export function EventsProvider({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  const [events, setEvents] = useState<RealtimeEvent[]>([]);
  const [connected, setConnected] = useState(false);
  const [toasts, setToasts] = useState<RealtimeEvent[]>([]);
  const [marketVersion, setMarketVersion] = useState(0);
  const clientRef = useRef<Client | null>(null);

  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const token = session?.access_token ?? null;

  useEffect(() => {
    // No session, no socket. Signing out drops the connection along with the events.
    if (!token || !WS_URL) {
      setConnected(false);
      setEvents([]);
      return;
    }

    const client = new Client({
      brokerURL: WS_URL,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        setConnected(true);
        // Personal events: this user's orders, fills, billing and access.
        client.subscribe(USER_QUEUE, (message: IMessage) => {
          try {
            const event = JSON.parse(message.body) as RealtimeEvent;
            setEvents((prev) => [event, ...prev].slice(0, MAX_EVENTS));
            setToasts((prev) => [event, ...prev].slice(0, 4));
            window.setTimeout(() => dismissToast(event.id), 5000);
          } catch (err) {
            console.error("Failed to parse realtime event", err);
          }
        });

        // Market activity from anyone. Only bumps a counter: this is not the user's own
        // activity, so it must not enter the feed or pop a toast — it exists so pages
        // showing market data can refetch when a listing is taken or returned.
        client.subscribe(MARKET_TOPIC, () => {
          setMarketVersion((v) => v + 1);
        });
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    });

    client.activate();
    clientRef.current = client;

    return () => {
      void client.deactivate();
      clientRef.current = null;
    };
  }, [token, dismissToast]);

  const clear = useCallback(() => setEvents([]), []);

  return (
    <EventsContext.Provider value={{ events, connected, marketVersion, clear }}>
      {children}
      <ToastStack toasts={toasts} onDismiss={dismissToast} />
    </EventsContext.Provider>
  );
}
