import { Suspense } from "react";
import { AuthForm } from "@/components/AuthForm";

export const metadata = { title: "Sign in — ExGPU" };

export default function LoginPage() {
  // AuthForm reads ?next= via useSearchParams, which requires a Suspense boundary
  // for Next's static prerender to succeed.
  return (
    <Suspense>
      <AuthForm mode="signin" />
    </Suspense>
  );
}
