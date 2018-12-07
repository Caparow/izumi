package com.github.pshirshov.izumi.idealingua.il.loader

import com.github.pshirshov.izumi.fundamentals.platform.exceptions.IzThrowable._
import com.github.pshirshov.izumi.idealingua.model.il.ast.IDLTyper
import com.github.pshirshov.izumi.idealingua.model.il.ast.raw.CompletelyLoadedDomain
import com.github.pshirshov.izumi.idealingua.model.loader._
import com.github.pshirshov.izumi.idealingua.model.problems.IDLDiagnostics
import com.github.pshirshov.izumi.idealingua.model.problems.TypespaceError.VerificationException
import com.github.pshirshov.izumi.idealingua.model.typespace.verification.{TypespaceVerifier, VerificationRule}
import com.github.pshirshov.izumi.idealingua.model.typespace.{Typespace, TypespaceImpl}


class ModelResolver(rules: Seq[VerificationRule]) {

  def resolve(domains: UnresolvedDomains): LoadedModels = LoadedModels {
    val importResolver = new ExternalRefResolver(domains)

    domains.domains.results
      .map(importResolver.resolveReferences)
      .map(makeTyped)
  }


  private def makeTyped(f: Either[LoadedDomain.Failure, CompletelyLoadedDomain]): LoadedDomain = {
    (for {
      d <- f
      ts <- runTyper(d)
      result <- runVerifier(ts)
    } yield {
      result
    }).fold(identity, identity)
  }


  private def runVerifier(ts: Typespace): Either[LoadedDomain.VerificationFailed, LoadedDomain.Success] = {
    try {
      val issues = new TypespaceVerifier(ts, rules).verify()
      if (issues.issues.isEmpty) {
        Right(LoadedDomain.Success(ts.domain.meta.origin, ts, issues.warnings))
      } else {
        Left(LoadedDomain.VerificationFailed(ts.domain.meta.origin, ts.domain.id, issues))
      }
    } catch {
      case t: Throwable =>
        Left(LoadedDomain.VerificationFailed(ts.domain.meta.origin, ts.domain.id, IDLDiagnostics(Vector(VerificationException(t.stackTrace)))))
    }
  }

  private def runTyper(d: CompletelyLoadedDomain): Either[LoadedDomain.TyperFailed, TypespaceImpl] = {
    (for {
      domain <- new IDLTyper(d).perform()
    } yield {
      new TypespaceImpl(domain)
    }).fold(issues => Left(LoadedDomain.TyperFailed(d.origin, d.id, issues)), Right.apply)
  }
}