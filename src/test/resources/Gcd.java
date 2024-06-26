// This file is part of KeY - Integrated Deductive Software Design 
//
// Copyright (C) 2001-2011 Universitaet Karlsruhe (TH), Germany 
//                         Universitaet Koblenz-Landau, Germany
//                         Chalmers University of Technology, Sweden
// Copyright (C) 2011-2013 Karlsruhe Institute of Technology, Germany 
//                         Technical University Darmstadt, Germany
//                         Chalmers University of Technology, Sweden
//
// The KeY system is protected by the GNU General 
// Public License. See LICENSE.TXT for details.
// 


public class Gcd {
	/*@   requires (a <= 100 && b <= 100 && a > 0 && b > 0 && a >= b);
      @   ensures (a != 0 || b != 0) && \result != 0 ==>
      @           (a % \result == 0 && b % \result == 0 &&
      @            (\forall int x; x > 0 && x <= a; (a % x == 0 && b % x == 0) ==>
      @                            \result % x == 0));
      @*/
	public static int gcd(int a, int b) {
		if (a < 0) a = -a;
		if (b < 0) b = -b;

		int big, small;
		big = a;
		small = b;

		return gcdHelp(big, small);
	}

	/*@
      @   requires _big <= 100 && _small >= 0 && _big >= _small;
      @   ensures _big != 0  && \result != 0 ==>
      @           (_big % \result == 0 && _small % \result == 0 &&
      @            (\forall int x; x > 0  && x <= _big; (_big % x == 0 && _small % x == 0) ==>
      @                            \result % x == 0));
      @ assignable \nothing;
      @*/
	private static int gcdHelp(int _big, int _small) {
		int big = _big;
		int small = _small;
		/*@
	      @ loop_invariant small >= 0 && big >= small &&
	      @   (big == 0 ==> _big == 0) &&
	      @   (\forall int x; x > 0 && x <= big; (_big % x == 0 && _small % x == 0)
	      @                          <==>
	      @                          (big % x == 0 && small % x == 0));
	      @ decreases small;
	      @ assignable \nothing;
	      @*/
		while (small != 0) {
			final int t = big % small;
			big = small;
			small = t;
		}
		return big;
	}

	/*@
      @   requires _big <= 100 && _small >= 0 && _big >= _small;
      @   ensures _big != 0  && \result != 0 ==>
      @           (_big % \result == 0 && _small % \result == 0 &&
      @            (\forall int x; x > 0  && x <= _big; (_big % x == 0 && _small % x == 0) ==>
      @                            \result % x == 0));
      @ assignable \nothing;
      @*/
	private static int gcdHelp2(int _big, int _small) {
		int big = _big;
		int small = _small;
		while (small != 0) {
			final int t = big % small;
			big = small;
			small = t;
		}
		return big;
	}
}
