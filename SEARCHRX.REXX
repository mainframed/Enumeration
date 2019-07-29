/* WREXXX-N-EFFECT                            */
/* All I want to do is zoom-a-zoom-zoom-zoom  */
/* And a poom-poom, just shake ya rump        */

ADDRESS TSO
SAY '*** Datasets in WARNING mode'
call sayit("SR ALL WARNING NOMASK")
SAY '*** READ or greater datasets'
call sayit("SR FILTER(**)")
say '*** Unix Privileged resources'
call sayit("SR CLASS(UNIVPRIV)")
say '*** BPX Access'
call sayit("SEARCH CLASS(FACILITY) FILTER(BPX.**)")
say '*** Surrogate Access'
call sayit("SEARCH CLASS(SURROGAT) FILTER(*.SUBMIT)")


sayit:
parse arg cmd
rump = OUTTRAP('shaker.')
ADDRESS TSO CMD
lines = shaker.0 + 1
DO i = lines TO shaker.0
  SAY shaker.i                                  /* displays 5 members */
END
